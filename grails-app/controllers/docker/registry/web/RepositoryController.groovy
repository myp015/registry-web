package docker.registry.web

import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Value

class RepositoryController {
  @Value('${registry.readonly}')
  boolean readonly
  int recordsPerPage = 100

  @Value('${registry.name}')
  String registryName

  def restService
  def authService

  //{"Type":"registry","Name":"catalog","Action":"*"}
  def index() {
    def repoCount = []
    boolean pagination = false
    def next = null
    boolean hasNext = false
    def message
    def url = "_catalog?n=${recordsPerPage}"
    try {
      if (params.start) {
        url += "&last=${params.start}"
      }
      def restResponse = restService.get(url, restService.generateAccess('catalog', '*', 'registry'))
      if (!restResponse.statusCode.'2xxSuccessful') {
        def statusCode = restResponse.statusCode
        log.warn "URI: '$url' responseCode: ${statusCode}"
        message = "status=${statusCode} ${statusCode.name()} ${restResponse.text}"

      }
      hasNext = restResponse.headers.getFirst('Link') != null
      pagination = hasNext || params.prev != null
      def repos = restResponse.json.repositories
      next = repos ? repos.last() : null

      repoCount = repos.collect { name ->
        def tagsCount = getTagList(name).size()
        [name: name, tags: tagsCount]
      }
    } catch (e) {
      log.error "Can't access registry: $url", e
      message = e.message
    }
    [repos: repoCount, pagination: pagination, next: next, prev: params.start, hasNext: hasNext, registryName: registryName, message: message]
  }

  def tags() {
    String name = params.id.decodeURL()
    def tagList = getTagList(name)

    if (!tagList) {
      log.warn "Repo name: ${name} has no tags, redirecting to home page"
      redirect action: 'index'
      return
    }

    def tags = getTags(name, tagList)
    def deletePermitted = authService.checkLocalDeletePermissions(name)
    [tags: tags, readonly: readonly || !deletePermitted, registryName: registryName]
  }


  private def getTags(name, List tagList = null) {
    def sourceTags = (tagList ?: getTagList(name)).findAll { it }
    sourceTags.collect { tag ->
      def manifest = restService.get("${name}/manifests/${tag}", restService.generateAccess(name), true)
      def manifestOk = manifest.statusCode.'2xxSuccessful'
      def resolvedManifest = manifestOk ? resolveSchema2Manifest(name, manifest.json) : null

      def topLayer
      def size = 0
      def layers = [:]
      if (resolvedManifest) {
        try {
          def v1Compat = resolvedManifest?.history?.first()?.v1Compatibility
          if (v1Compat) {
            topLayer = new JsonSlurper().parseText(v1Compat)
          }
        } catch (e) {
          log.debug "No v1Compatibility available for ${name}:${tag}"
        }

        layers = getLayersFromManifestJson(name, resolvedManifest)
        size = layers ? layers.collect { it.value }.sum() : 0
      }

      def createdStr = topLayer?.created
      def createdDate = DateConverter.convert(createdStr)
      long unixTime = createdDate?.time ?: 0

      // Tag existence comes from /tags/list, not manifest details.
      [
        name    : tag,
        count   : layers?.size() ?: 0,
        size    : size,
        exists  : true,
        id      : topLayer?.id?.substring(0, 11) ?: shortDigest(resolvedManifest?.config?.digest),
        created : createdDate,
        createdStr: createdStr,
        unixTime: unixTime,
        readable: manifestOk
      ]
    }
  }

  private def getLayers(String name, String tag) {
    def json = restService.get("${name}/manifests/${tag}", restService.generateAccess(name), true).json
    def resolved = resolveSchema2Manifest(name, json)
    getLayersFromManifestJson(name, resolved)
  }

  private def getLayersFromManifestJson(String name, def json) {
    if (json?.schemaVersion == 2 && json?.layers) {
      return json.layers.collectEntries { [it.digest, it.size as BigInteger] }
    }

    if (json?.history && json?.fsLayers) {
      // fallback to manifest schema v1
      def history = json.history.v1Compatibility.collect { jsonValue ->
        new JsonSlurper().parseText(jsonValue)
      }

      def digests = json.fsLayers.collect { it.blobSum }
      history.eachWithIndex { entry, i ->
        entry.digest = digests[i]
        entry.size = entry.Size ?: 0
      }

      return history.collectEntries {
        [it.digest, it.size as BigInteger]
      }
    }

    [:]
  }

  // Resolve a manifest-list / OCI index to a concrete image manifest.
  private def resolveSchema2Manifest(String name, def json) {
    if (!(json?.schemaVersion == 2)) {
      return json
    }

    if (json?.layers) {
      return json
    }

    if (json?.manifests) {
      def picked = json.manifests.find {
        it?.platform?.os == 'linux' && it?.platform?.architecture == 'amd64'
      } ?: json.manifests.find {
        it?.platform?.os == 'linux'
      } ?: json.manifests.first()

      if (picked?.digest) {
        def child = restService.get("${name}/manifests/${picked.digest}", restService.generateAccess(name), true)
        if (child.statusCode.'2xxSuccessful') {
          return child.json
        }
      }
    }

    json
  }

  private String shortDigest(String digest) {
    if (!digest) return null
    def clean = digest.contains(':') ? digest.split(':', 2)[1] : digest
    clean.substring(0, Math.min(clean.length(), 11))
  }

  private List getTagList(name) {
    restService.get("${name}/tags/list", restService.generateAccess(name)).json?.tags ?: []
  }

  def tag() {
    def name = params.id.decodeURL()
    def tag = params.name
    def res = restService.get("${name}/manifests/${tag}", restService.generateAccess(name), true).json
    def manifest = resolveSchema2Manifest(name, res)

    def history = []
    if (manifest?.history?.v1Compatibility) {
      history = manifest.history.v1Compatibility.collect { jsonValue ->
        def json = new JsonSlurper().parseText(jsonValue)
        [id: json.id?.substring(0, 11), cmd: (json?.container_config?.Cmd?.last() ?: '').replaceAll('&&', '&&\n')]
      }

      def blobs = manifest.fsLayers?.collect { it.blobSum } ?: []
      def layers = getLayersFromManifestJson(name, manifest)
      history.eachWithIndex { entry, i ->
        def digest = blobs[i]
        entry.size = layers[digest] ?: 0
      }
    } else if (manifest?.layers) {
      // schema v2 / OCI without v1Compatibility history
      history = manifest.layers.collect { layer ->
        [id: shortDigest(layer.digest), cmd: '(schema v2/oci layer)', size: (layer.size ?: 0)]
      }
    }

    [history: history, totalSize: history.sum { it.size ?: 0 }, registryName: registryName]
  }

  def delete() {
    String name = params.id.decodeURL()
    def tag = params.name
    if (!readonly) {
      def manifest = restService.get("${name}/manifests/${tag}", restService.generateAccess(name, 'pull'), true)
      def digest = manifest.responseEntity.headers.getFirst('Docker-Content-Digest')
      log.info "Manifest digest: $digest"
      /*
    def blobSums = manifest.json.fsLayers?.blobSum
    blobSums.each { digest ->
      log.info "Deleting blob: ${digest}"
      restService.delete("${name}/blobs/${digest}")
    }
    */
      if (authService.checkLocalDeletePermissions(name)) {
        log.info "Deleting manifest"
        def result = restService.delete("${name}/manifests/${digest}", restService.generateAccess(name, '*'))
        if (!result.deleted) {
          def text = ''
          try {
            boolean unsupported = result.response.json.errors[0].code == 'UNSUPPORTED'
            text = unsupported ? "Deletion disabled in registry, <a href='https://docs.docker.com/registry/configuration/#delete'>more info</a>." : result.text
          } catch (e) {
            log.warn "Error deleting", e
            text = result.text
          }
          flash.message = "Error deleting ${name}:${tag}: ${text}"
        }
      } else {
        log.warn 'Delete not allowed!'
        flash.message = "Delete not allowed!"
      }
    } else {
      log.warn 'Readonly mode!'
      flash.message = "Readonly mode!"
    }
    flash.deleteAction = true
    redirect action: 'tags', id: params.id
  }
}
