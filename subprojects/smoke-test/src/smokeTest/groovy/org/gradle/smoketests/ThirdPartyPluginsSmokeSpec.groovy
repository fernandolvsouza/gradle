/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.smoketests

import org.gradle.util.ports.ReleasingPortAllocator
import org.junit.Rule
import spock.lang.Ignore

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class ThirdPartyPluginsSmokeSpec extends AbstractSmokeSpec {
    @Rule final ReleasingPortAllocator portAllocator = new ReleasingPortAllocator()

    def 'shadow plugin'() {
        given:
        buildFile << """
            import com.github.jengelman.gradle.plugins.shadow.transformers.ServiceFileTransformer

            plugins {
              id 'java' // or 'groovy' Must be explicitly applied
              id 'com.github.johnrengelman.shadow' version '1.2.3'
            }

            repositories {
               jcenter()
            }

            dependencies {
              compile 'commons-collections:commons-collections:3.2.2'
            }

            shadowJar {

              transform(ServiceFileTransformer)

              manifest {
                attributes 'Test-Entry': 'PASSED'
              }
            }
            """.stripIndent()

        when:
        def result = runner('shadowJar').build()

        then:
        result.task(':shadowJar').outcome == SUCCESS
    }

    def 'kotlin plugin'() {
        given:
        def kotlinVersion = '1.0.2'
        buildFile << """
            buildscript {
               ext.kotlin_version = '$kotlinVersion'

               repositories {
                 mavenCentral()
               }

               dependencies {
                 classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion"
               }
            }

            apply plugin: 'kotlin'

            repositories {
               mavenCentral()
            }

            dependencies {
              compile "org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion"
            }
        """.stripIndent()

        file('src/main/kotlin/pkg/HelloWorld.kt') << """
        package pkg

        fun getGreeting(): String {
            val words = mutableListOf<String>()
            words.add("Hello,")
            words.add("world!")

            return words.joinToString(separator = " ")
        }

        fun main(args: Array<String>) {
            println(getGreeting())
        }
        """.stripIndent()

        when:
        def result = runner('build').build()

        then:
        result.task(':compileKotlin').outcome == SUCCESS
    }

    def 'asciidoctor plugin'() {
        given:
        buildFile << """
            buildscript {
                repositories {
                    jcenter()
                }

                dependencies {
                    classpath 'org.asciidoctor:asciidoctor-gradle-plugin:1.5.3'
                }
            }

            apply plugin: 'org.asciidoctor.convert'
            """.stripIndent()

        file('src/docs/asciidoc/test.adoc') << """
            = Line Break Doc Title
            :hardbreaks:

            Rubies are red,
            Topazes are blue.
            """.stripIndent()

        when:
        runner('asciidoc').build()

        then:
        file('build/asciidoc').isDirectory()
    }

    def 'docker plugin'() {
        given:
        buildFile << """
            buildscript {
                repositories {
                    jcenter()
                }

                dependencies {
                    classpath 'com.bmuschko:gradle-docker-plugin:3.0.0'
                }
            }

            apply plugin: 'java'
            apply plugin: 'application'
            apply plugin: 'com.bmuschko.docker-java-application'

            mainClassName = 'org.gradle.JettyMain'

            docker {
                javaApplication {
                    baseImage = 'dockerfile/java:openjdk-7-jre'
                    port = 9090
                    tag = 'jettyapp:1.115'
                }
            }
            """.stripIndent()

        when:
        def result = runner(':dockerCopyDistResources').build()

        then:
        result.task(':dockerCopyDistResources').outcome == SUCCESS
    }

    @Ignore("No service of type StyledTextOutputFactory available in ProjectScopeServices")
    def 'spring dependency management plugin'() {
        given:
        buildFile << """
            plugins {
                id "io.spring.dependency-management" version "0.5.6.RELEASE"
            }

            apply plugin: 'java'
            apply plugin: "io.spring.dependency-management"

            repositories {
                mavenCentral()
            }

            dependencyManagement {
                dependencies {
                    dependency 'org.springframework:spring-core:4.0.3.RELEASE'
                    dependency group: 'commons-logging', name: 'commons-logging', version: '1.1.2'
                }
            }

            dependencies {
                 compile 'org.springframework:spring-core'
            }
            """.stripIndent()

        expect:
        runner("dependencies", "--configuration", "compile").build()
    }

    def 'tomcat plugin'() {
        given:
        def httpPort = portAllocator.assignPort()
        def httpsPort = portAllocator.assignPort()
        def stopPort = portAllocator.assignPort()
        buildFile << """
            buildscript {
                repositories {
                    jcenter()
                }

                dependencies {
                    classpath 'com.bmuschko:gradle-tomcat-plugin:2.2.5'
                }
            }

            apply plugin: 'com.bmuschko.tomcat'

            repositories {
                mavenCentral()
            }

            dependencies {
                def tomcatVersion = '7.0.59'
                tomcat "org.apache.tomcat.embed:tomcat-embed-core:\${tomcatVersion}",
                       "org.apache.tomcat.embed:tomcat-embed-logging-juli:\${tomcatVersion}",
                       "org.apache.tomcat.embed:tomcat-embed-jasper:\${tomcatVersion}"
            }

            ext {
                tomcatStopPort = ${stopPort}
                tomcatStopKey = 'stopKey'
            }

            tomcat {
                httpPort = ${httpPort}
                httpsPort = ${httpsPort}
            }

            task integrationTomcatRun(type: com.bmuschko.gradle.tomcat.tasks.TomcatRun) {
                stopPort = tomcatStopPort
                stopKey = tomcatStopKey
                daemon = true
            }

            task integrationTomcatStop(type: com.bmuschko.gradle.tomcat.tasks.TomcatStop) {
                stopPort = tomcatStopPort
                stopKey = tomcatStopKey
            }

            task integrationTest(type: Test) {
                include '**/*IntegrationTest.*'
                dependsOn integrationTomcatRun
                finalizedBy integrationTomcatStop
            }

            test {
                exclude '**/*IntegrationTest.*'
            }
            """.stripIndent()

        expect:
        runner('integrationTest').build()
    }

    def 'gosu plugin'() { // Requires JDK 8 or later
        given:
        buildFile << """
            plugins {
                id "org.gosu-lang.gosu" version "0.1.3"
            }

            apply plugin: "org.gosu-lang.gosu"

            repositories {
                mavenCentral()
            }

            dependencies {
                compile group: 'org.gosu-lang.gosu', name: 'gosu-core-api', version: '1.10'
            }
            """.stripIndent()

        file('src/main/gosu/example/Foo.gs') << """
            package example

            public class Foo {

              function doSomething(arg : String) : String {
                return "Hello, got the argument '\${arg}'"
              }

            }
            """.stripIndent()


        when:
        def result = runner('build').build()

        then:
        result.task(':compileGosu').outcome == SUCCESS
    }

    def 'xtend plugin'() {
        given:
        buildFile << """
            plugins {
              id "org.xtext.xtend" version "1.0.5"
            }

            repositories.jcenter()

            dependencies {
              compile 'org.eclipse.xtend:org.eclipse.xtend.lib:2.9.0'
            }
            """.stripIndent()

        file('src/main/java/HelloWorld.xtend') << """
            class HelloWorld {
              def static void main(String[] args) {
                println("Hello World")
              }
            }
            """

        when:
        def result = runner('build').build()

        then:
        result.task(':generateXtext').outcome == SUCCESS
    }
}
