buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath "com.android.tools.build:gradle:8.1.0"
        kotlin "org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.2"
    }
}

task clean(type) {
    delete rootProject.buildDir
}