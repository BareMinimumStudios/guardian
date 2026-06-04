plugins {
	`maven-publish`
	kotlin("jvm") version libs.versions.kotlin
	kotlin("plugin.serialization") version libs.versions.kotlin
	alias(libs.plugins.cloche)
}

group = "xyz.naomieow.guardian"
version = "1.0.0"

repositories {
	cloche.librariesMinecraft()

	mavenCentral()

	cloche {
		main()
		mavenFabric()
		mavenParchment()
	}

	maven("https://api.modrinth.com/maven")
	maven("https://maven.nucleoid.xyz")
	maven("https://maven.is-immensely.gay/releases")
}

cloche {
	metadata {
		modId = "guardian"
		name = "Guardian"
		description = ""
		license = "BML-1.0"

		author {
			name = "naomieow"
			contact = "https://github.com/naomieow"
		}

		url = "https://github.com/BareMinimumStudios/guardian"
		sources = "https://github.com/BareMinimumStudios/guardian"
		issues = "https://github.com/BareMinimumStudios/guardian/issues"

		icon = "assets/guardian/icon.png"
	}

	common {
		mixins.from(file("src/main/guardian.mixins.json"))

		dependencies {
			modImplementation(libs.fabric.kotlin)

			implementation(libs.exposed.core)
			implementation(libs.exposed.jdbc)
			implementation(libs.exposed.json)
			implementation(libs.exposed.kotlin.datetime)
			implementation(libs.mysql)
			implementation(libs.sqlite)
			implementation(libs.h2)
			implementation(libs.ktoml.core)
			implementation(libs.ktoml.file)
			implementation(libs.nullevt)
		}
	}

	fabric("1.20.1") {
		minecraftVersion = "1.20.1"
		loaderVersion = libs.versions.fabric.loader

		mappings {
			official()
			parchment(libs.versions.parchment.get1201())
		}

		runs {
			server()
		}

		dependencies {
			fabricApi(libs.versions.fabric.api.get1201())

			modImplementation(libs.opc.get1201())
			include(libs.opc.get1201())
			modImplementation(libs.sgui.get1201())
			include(libs.sgui.get1201())
			modImplementation(libs.permissionsapi.get1201())
			include(libs.permissionsapi.get1201())
		}

		metadata {
			dependencies {
				dependency {
					modId = "fabric-api"
					version(libs.versions.fabric.api.get1201().get())
				}
				dependency {
					modId = "fabric-language-kotlin"
					version(libs.versions.fabric.kotlin.get())
				}
			}

			entrypoint("main") {
				adapter = "kotlin"
				value = "xyz.naomieow.guardian.Guardian"
			}
		}
	}

	fabric("1.21.1") {
		minecraftVersion = "1.21.1"
		loaderVersion = libs.versions.fabric.loader

		mappings {
			official()
			parchment(libs.versions.parchment.get1211())
		}

		runs {
			server()
		}

		dependencies {
			fabricApi(libs.versions.fabric.api.get1211())

			modImplementation(libs.opc.get1211())
			include(libs.opc.get1211())
			modImplementation(libs.sgui.get1211())
			include(libs.sgui.get1211())
			modImplementation(libs.permissionsapi.get1211())
			include(libs.permissionsapi.get1211())
		}

		metadata {
			dependencies {
				dependency {
					modId = "fabric-api"
					version(libs.versions.fabric.api.get1211().get())
				}
				dependency {
					modId = "fabric-language-kotlin"
					version(libs.versions.fabric.kotlin.get())
				}
			}

			entrypoint("main") {
				adapter = "kotlin"
				value = "xyz.naomieow.guardian.Guardian"
			}
		}
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs = listOf("-Xmulti-platform", "-Xno-check-actual", "-Xexpect-actual-classes")
	}
}
