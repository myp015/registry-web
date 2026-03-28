<%@ page contentType="text/html;charset=UTF-8" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <title>Tags</title>
    <script>
        $(document).ready(function () {
            $('#main').DataTable({
                "paging": false,
                "searching": false,
                "info": false,
                "order": [[2, "desc"]],
                "columnDefs": [
                    {orderable: false, targets: -1}
                ]
            });
        });
    </script>
</head>

<body>
<g:modal id="deleteTag" title="Confirm Delete" fields="['tag', 'id']">
    <p>You are about to delete tag <strong id="tag"></strong> and all images with id <strong id="id"></strong>.</p>

    <p>Do you want to proceed?</p>
</g:modal>
<div class="row">
    <g:header title='Tags'>
        <li><g:link action="index">Home</g:link></li>
        <li class="active">${params.id.decodeURL()}</li>
    </g:header>
    <div class="col-md-12">
        <g:if test="${flash.deleteAction}">
            <div class="alert alert-${flash.success ? 'success' : 'danger'}" role="alert">
                ${raw(flash.message)}
            </div>
        </g:if>
        <dl>
            <dt>Repository</dt>
            <dd>${registryName}/${params.id.decodeURL()}</dd>
        </dl>

        <div class="table-responsive">
            <table class="table table-bordered table-hover" id="main">
                <thead>
                <tr>
                    <th>Id</th>
                    <th>Tag</th>
                    <th>Created</th>
                    <th>Platform</th>
                    <th>Digest</th>
                    <th>Layers</th>
                    <th>Size</th>
                    <g:if test="${!readonly}">
                        <th>Delete</th>
                    </g:if>
                </tr>
                </thead>
                <tbody>
                <g:each in="${tags}" var="tag">
                    <g:if test="${tag.exists}">
                        <tr>
                            <td>${tag.id ?: '-'}</td>
                            <td>
                                <g:link action="tag" params="[name: tag.name, digest: tag.digest, manifestDigest: tag.manifestDigest, platform: tag.platform]"
                                        id="${params.id}">${tag.name}</g:link>
                            </td>
                            <td data-sort="${tag.unixTime}">
                                <g:if test="${tag.created}">
                                    <abbr title="${tag.createdStr}"><prettytime:display date="${tag.created}"/></abbr>
                                </g:if>
                                <g:else>-</g:else>
                            </td>
                            <td>${tag.platform ?: '-'}</td>
                            <td><code>${tag.digestShort ?: '-'}</code></td>
                            <td>${tag.count ?: 0}</td>
                            <td data-sort="${tag.size ?: 0}"><g:formatSize value="${tag.size ?: 0}"/></td>
                            <g:if test="${!readonly}">
                                <td>
                                    <a href="#" data-tag="${tag.name}" data-id="${tag.id}"
                                       data-href="${g.createLink(action: 'delete', params: [id: params.id, name: tag.name])}"
                                       data-toggle="modal" data-target="#deleteTag">Delete</a>
                                </td>
                            </g:if>
                        </tr>
                    </g:if>
                </g:each>
            </table>
        </div>
    </div>
</div>
</body>
</html>
