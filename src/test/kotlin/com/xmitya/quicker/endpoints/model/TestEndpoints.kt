package com.xmitya.quicker.endpoints.model

import com.xmitya.quicker.endpoints.match.EndpointInfo
import com.xmitya.quicker.endpoints.match.EndpointKind
import com.xmitya.quicker.endpoints.match.HttpVerb
import com.xmitya.quicker.endpoints.match.parseSegments

/**
 * An endpoint built without PSI, as a model restored from disk looks: names, a file URL and an
 * offset, no smart pointer. Shared by the tests that care about the data rather than the scan.
 */
internal fun testEndpoint(
    path: String,
    controller: String,
    method: String,
    verb: HttpVerb = HttpVerb.GET,
    kind: EndpointKind = EndpointKind.CONTROLLER,
    unresolved: Boolean = false,
    test: Boolean = false,
    module: String? = "svc.main",
    fileUrl: String = "file:///repo/$controller.java",
    offset: Int = 42,
) = Endpoint(
    EndpointInfo(
        verb = verb,
        path = path,
        segments = parseSegments(path),
        controllerSimpleName = controller,
        methodName = method,
        kind = kind,
        unresolved = unresolved,
        inTestSource = test,
        moduleName = module,
    ),
    EndpointLocator(fileUrl, offset, "demo.$controller", method),
)
