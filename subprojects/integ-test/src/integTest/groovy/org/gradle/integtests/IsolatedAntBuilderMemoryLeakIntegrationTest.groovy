/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.integtests

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.test.fixtures.file.TestFile
import spock.lang.Unroll

class IsolatedAntBuilderMemoryLeakIntegrationTest extends AbstractIntegrationSpec {

    private void goodCode(String groovyVersion, TestFile root = testDirectory) {
        root.file("src/main/java/org/gradle/Class0.java") << "package org.gradle; public class Class0 { }"
        root.file("src/main/groovy/org/gradle/Class1.groovy") << "package org.gradle; class Class1 { }"
        buildFile << """

            allprojects {
                repositories {
                    jcenter()
                }
            }

            allprojects {
                apply plugin: 'groovy'

                dependencies {
                    compile $groovyVersion
                }
            }
        """
    }

    private void withCodenarc(String groovyVersion, TestFile root = testDirectory) {
        root.file("config/codenarc/rulesets.groovy") << """
            ruleset {
                ruleset('rulesets/naming.xml')
            }
        """
        buildFile << """
            allprojects {
                apply plugin: 'codenarc'

                dependencies {
                    codenarc('org.codenarc:CodeNarc:0.24.1') {
                        exclude group: 'org.codehaus.groovy'
                    }
                    codenarc $groovyVersion
                }

                codenarc {
                    configFile = file('config/codenarc/rulesets.groovy')
                }

            }
"""
    }

    private void withCheckstyle(TestFile root = testDirectory) {
        root.file("config/checkstyle/checkstyle.xml") << """<!DOCTYPE module PUBLIC
        "-//Puppy Crawl//DTD Check Configuration 1.2//EN"
        "http://www.puppycrawl.com/dtds/configuration_1_2.dtd">
<module name="Checker">
</module>
        """
        buildFile << """
            allprojects {
                apply plugin: 'checkstyle'
            }
"""
    }

    @Unroll
    void 'CodeNarc does not fail with PermGen space error, Groovy #groovyVersion'() {
        given:
        withCodenarc(groovyVersion)
        withCheckstyle()
        goodCode(groovyVersion)

        expect:
        succeeds 'check'

        where:
        groovyVersion << [
            'localGroovy()',
            "'org.codehaus.groovy:groovy-all:2.3.10'",
            "'org.codehaus.groovy:groovy-all:2.2.1'",
            "'org.codehaus.groovy:groovy-all:2.1.9'",
            "'org.codehaus.groovy:groovy-all:2.0.4'",
            "'org.codehaus.groovy:groovy-all:1.8.7'"] * 3
    }

    @Unroll
    void "Doesn't fail with a PermGen space error or a missing method exception"() {
        given:
        buildFile << '''
buildscript {
  repositories {
    maven {
      url "https://plugins.gradle.org/m2/"
    }
  }

  dependencies {
    classpath "org.ajoberstar:gradle-git:1.3.0"
  }
}

import org.ajoberstar.grgit.*
Grgit.open(currentDir: project.rootProject.rootDir)

'''
        withCheckstyle()
        goodCode('localGroovy()')

        expect:
        succeeds 'check'

        where:
        iteration << (0..10)
    }
}
