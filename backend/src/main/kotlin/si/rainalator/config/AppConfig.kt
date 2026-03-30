package si.rainalator.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault

@ConfigMapping(prefix = "rainalator")
interface AppConfig {

    @WithDefault("https://meteo.arso.gov.si/uploads/probase/www/observ/radar/si0-rrg.srd")
    fun arsoUrl(): String

    @WithDefault("5")
    fun fetchIntervalMinutes(): Int
}
