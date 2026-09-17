package com.xmitya.quicker.endpoints.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Application-level rather than per-project: this is a preference about how the developer wants
 * their IDE to behave, and a per-project copy would end up in `.idea` and travel to everyone who
 * clones the repository.
 */
@Service(Service.Level.APP)
@State(name = "QuickerEndpoints", storages = [Storage("quicker-endpoints.xml")])
class EndpointSettings : SimplePersistentStateComponent<EndpointSettings.State>(State()) {

    class State : BaseState() {
        /**
         * Warm the model when a project opens, so the first search is instant. Worth it on a large
         * monorepo; wasted work on a handful of small projects that may never be searched.
         */
        var scanOnStartup: Boolean by property(true)

        /**
         * Keep the resolved model on disk between sessions so search works the instant a project
         * opens, while a fresh scan runs behind it.
         */
        var persistCache: Boolean by property(true)

        /**
         * Rescan after edits. Off by default: there is no incremental update yet, so any change to
         * a Java or Kotlin file invalidates the whole model and the next search would pay for a
         * full rescan — too steep a price on a large repository for someone editing steadily.
         *
         * The model is still built at startup and can be refreshed from settings, so it is never
         * left unbuilt; it just stops chasing every keystroke.
         */
        var rebuildOnChange: Boolean by property(false)
    }

    companion object {
        fun getInstance(): EndpointSettings = service()
    }
}
