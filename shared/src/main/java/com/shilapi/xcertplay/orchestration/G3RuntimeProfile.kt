package com.shilapi.xcertplay.orchestration

/** Enforced before the controller acquires services, starts workers, or requests permissions. */
internal object G3RuntimeProfile {
    fun requireSupported(config: CarPlayRuntimeConfig) {
        require(config.transport == CarPlayTransport.WIRELESS) {
            "DiPlay G3 supports wireless CarPlay through the car hotspot only"
        }
        require(config.wirelessHotspotMode == WirelessHotspotMode.MANUAL) {
            "DiPlay G3 requires the car's own hotspot"
        }
        require(config.mfiTarget == MfiTarget.LOCAL) {
            "DiPlay G3 requires local offline authentication"
        }
    }
}
