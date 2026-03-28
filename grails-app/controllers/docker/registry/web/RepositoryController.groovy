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

    sourceTags.collectMany { tag ->
      def manifestResp = restService.get("${name}/manifests/${tag}", restService.generateAccess(name), true)
      def manifestOk = manifestResp.statusCode.'2xxSuccessful'
      if (!manifestOk || !manifestResp.json) {
        return [[name: tag, exists: false, readable: false, platform: '-', digest: null, digestShort: null, count: 0, size: 0, id: '-', created: null, createdStr: null, unixTime: 0]]
      }

      def json = manifestResp.json
      // Multi-arch manifest list / OCI index: expand one row per real platform
      if (isManifestIndex(json)) {
        def children = (json.manifests ?: []).findAll {
          isRealPlatform(it?.platform)
        }

        if (children) {
          return children.collect { child ->
            def childDigest = child?.digest
            def childResp = childDigest ? restService.get("${name}/manifests/${childDigest}", restService.generateAccess(name), true) : null
            def childOk = childResp?.statusCode?.'2xxSuccessful'
            def childJson = childOk ? childResp.json : null
            buildTagEntry(name, tag, childJson, true, platformString(child?.platform), childDigest)
          }
        }
      }

      // Single-arch or fallback: one row
      def resolvedManifest = resolveSchema2Manifest(name, json)
      [buildTagEntry(name, tag, resolvedManifest, true, detectPlatformFromManifest(json, resolvedManifest), null)]
    }
  }

  private Map buildTagEntry(String repoName, String tag, def manifestJson, boolean exists, String platform = '-', String digest = null) {
    def topLayer
    def size = 0
    def layers = [:]

    if (manifestJson) {
      try {
        def v1Compat = manifestJson?.history?.first()?.v1Compatibility
        if (v1Compat) {
          topLayer = new JsonSlurper().parseText(v1Compat)
        }
      } catch (e) {
        log.debug "No v1Compatibility available for ${tag}"
      }

      layers = getLayersFromManifestJson(manifestJson)
      size = layers ? layers.collect { it.value }.sum() : 0
    }

    def createdStr = topLayer?.created
    if (!createdStr && manifestJson?.config?.digest) {
      def cfg = fetchConfigBlob(repoName, manifestJson.config.digest)
      createdStr = cfg?.created ?: cfg?.history?.find { it?.created }?.created
    }
    def createdDate = DateConverter.convert(createdStr)
    long unixTime = createdDate?.time ?: 0

    def effectiveDigest = digest ?: manifestJson?.config?.digest
    [
      name      : tag,
      count     : layers?.size() ?: 0,
      size      : size,
      exists    : exists,
      id        : topLayer?.id?.substring(0, 11) ?: shortDigest(effectiveDigest),
      created   : createdDate,
      createdStr: createdStr,
      unixTime  : unixTime,
      readable  : manifestJson != null,
      platform  : platform ?: '-',
      digest    : effectiveDigest,
      digestShort: shortDigest(effectiveDigest)
    ]
  }

  private def getLayers(String name, String tag) {
    def json = restService.get("${name}/manifests/${tag}", restService.generateAccess(name), true).json
    def resolved = resolveSchema2Manifest(name, json)
    getLayersFromManifestJson(resolved)
  }

  private def getLayersFromManifestJson(def json) {
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

  private boolean isManifestIndex(def json) {
    (json?.schemaVersion == 2 && json?.manifests)
  }

  private boolean isRealPlatform(def p) {
    if (!p) return false
    p.os && p.architecture && !(p.os == 'unknown' || p.architecture == 'unknown')
  }

  private String platformString(def p) {
    if (!p?.os || !p?.architecture) return '-'
    "${p.os}/${p.architecture}"
  }

  private String detectPlatformFromManifest(def originalJson, def resolvedManifest) {
    // If we resolved from index to a child manifest, platform may not be present in child;
    // keep generic marker for legacy single manifest cases.
    if (originalJson?.platform?.os && originalJson?.platform?.architecture) {
      return platformString(originalJson.platform)
    }
    return '-'
  }

  private String shortDigest(String digest) {
    if (!digest) return null
    def clean = digest.contains(':') ? digest.split(':', 2)[1] : digest
    clean.substring(0, Math.min(clean.length(), 11))
  }

  private def fetchConfigBlob(String name, String digest) {
    try {
      if (!name || !digest) return null
      def resp = restService.get("${name}/blobs/${digest}", restService.generateAccess(name), true)
      return resp?.statusCode?.'2xxSuccessful' ? resp?.json : null
    } catch (e) {
      log.debug "Unable to fetch config blob for ${name}@${digest}", e
      return null
    }
  }

  private String renderCmdFromHistoryEntry(def json) {
    def cmdList = json?.container_config?.Cmd ?: json?.config?.Cmd
    if (cmdList instanceof Collection && cmdList) {
      return cmdList.join(' ').replaceAll('&&', '&&\n')
    }
    if (cmdList instanceof String) {
      return cmdList.replaceAll('&&', '&&\n')
    }
    def createdBy = json?.created_by
    if (createdBy) {
      return createdBy.toString().replaceAll('&&', '&&\n')
    }
    return '(n/a)'
  }

  private String renderCmdFromLayer(def layer) {
    // OCI/Docker v2 layer has no direct command string; provide digest hint.
    return "layer ${shortDigest(layer?.digest)}"
  }

  private List getTagList(name) {
    restService.get("${name}/tags/list", restService.generateAccess(name)).json?.tags ?: []
  }

  def tag() {
    def name = params.id.decodeURL()
    def tag = params.name
    def digest = params.digest
    def target = digest ?: tag

    def res = restService.get("${name}/manifests/${target}", restService.generateAccess(name), true).json
    def manifest = resolveSchema2Manifest(name, res)

    def history = []
    if (manifest?.history?.v1Compatibility) {
      history = manifest.history.v1Compatibility.collect { jsonValue ->
        def json = new JsonSlurper().parseText(jsonValue)
        [id: json.id?.substring(0, 11), cmd: renderCmdFromHistoryEntry(json)]
      }

      def blobs = manifest.fsLayers?.collect { it.blobSum } ?: []
      def layers = getLayersFromManifestJson(manifest)
      history.eachWithIndex { entry, i ->
        def d = blobs[i]
        entry.size = layers[d] ?: 0
      }
    } else if (manifest?.layers) {
      // schema v2 / OCI without v1Compatibility history
      history = manifest.layers.collect { layer ->
        [id: shortDigest(layer.digest), cmd: renderCmdFromLayer(layer), size: (layer.size ?: 0)]
      }
    }

    [
      history    : history,
      totalSize  : history.sum { it.size ?: 0 },
      registryName: registryName,
      platform   : params.platform,
      digest     : digest
    ]
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
