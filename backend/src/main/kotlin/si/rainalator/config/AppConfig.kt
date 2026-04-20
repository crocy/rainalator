package si.rainalator.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault

@ConfigMapping(prefix = "rainalator")
interface AppConfig {

    @WithDefault("https://meteo.arso.gov.si/uploads/probase/www/observ/radar/si0-rrg.srd")
    fun arsoUrl(): String

    @WithDefault("5m")
    fun fetchInterval(): String

    @WithDefault("data/spill")
    fun spillDir(): String

    @WithDefault("data/raw-archive")
    fun rawArchiveDir(): String

    @WithDefault("rainalator-raw-archive")
    fun s3Bucket(): String

    @WithDefault("srd3/")
    fun s3Prefix(): String
}
