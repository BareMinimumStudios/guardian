rootProject.name = "guardian"

pluginManagement {
	repositories {
		maven("https://maven.msrandom.net/repository/cloche/")
		mavenCentral()
		gradlePluginPortal()
	}
}

dependencyResolutionManagement {
	versionCatalogs.create("libs") {
		from(files("libs.versions.toml"))
	}
}
