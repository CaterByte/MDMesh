package com.mdmesh.agent.di

import com.mdmesh.policy.CapabilityRegistry
import com.mdmesh.policy.TogglePolicy

/**
 * MeinConnect fork: a read-only map of the toggle policies that asks [CapabilityRegistry] afresh on every
 * access, so a holder created before the agent became Device Owner still sees the policies afterwards.
 */
internal class LiveTogglePolicies(private val registry: CapabilityRegistry) : AbstractMap<String, TogglePolicy>() {
    override val entries: Set<Map.Entry<String, TogglePolicy>>
        get() = registry.togglePolicies().entries

    override fun get(key: String): TogglePolicy? = registry.togglePolicies()[key]
}
