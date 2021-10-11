/* Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the 'License');
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an 'AS IS' BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.plugins.hibernate.search.mapper.orm.mapping


import grails.core.GrailsApplication
import grails.core.GrailsClass
import grails.plugins.hibernate.search.HibernateSearchGrailsPlugin
import grails.plugins.hibernate.search.config.SearchMappingEntityConfig
import groovy.util.logging.Slf4j
import org.grails.core.artefact.DomainClassArtefactHandler
import org.grails.datastore.mapping.reflect.ClassPropertyFetcher
import org.hibernate.search.mapper.orm.mapping.HibernateOrmMappingConfigurationContext
import org.hibernate.search.mapper.orm.mapping.HibernateOrmSearchMappingConfigurer
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.ProgrammaticMappingConfigurationContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.annotation.AnnotationUtils

import static grails.plugins.hibernate.search.HibernateSearchGrailsPlugin.INDEXED_ENTITIES_GRAILS_APP_CONFIG_KEY

/**
 * @since 11/10/2021
 */
@Slf4j
class HibernateSearchMappingConfigurer implements HibernateOrmSearchMappingConfigurer {

    private final static Logger pluginLogger = LoggerFactory.getLogger(HibernateSearchGrailsPlugin)

    @Autowired
    GrailsApplication grailsApplication
    private final Map<String, List<String>> indexedPropertiesByName

    HibernateSearchMappingConfigurer() {
        indexedPropertiesByName = [:]
    }

    @Override
    void configure(HibernateOrmMappingConfigurationContext context) {

        log.debug('Configuring Hibernate Search entities')
        ProgrammaticMappingConfigurationContext programmaticMappingConfigurationContext = context.programmaticMapping()

        Collection<GrailsClass> domainClasses = grailsApplication.getArtefacts(DomainClassArtefactHandler.TYPE)

        for (GrailsClass domainClass : domainClasses) {

            Closure searchClosure = ClassPropertyFetcher.forClass(domainClass.getClazz())
                .getStaticPropertyValue(SearchMappingEntityConfig.INDEX_CONFIG_NAME, Closure)

            if (searchClosure != null) {
                pluginLogger.info '* {} is indexed', domainClass.name

                SearchMappingEntityConfig searchMappingEntityConfig = new SearchMappingEntityConfig(programmaticMappingConfigurationContext, domainClass)

                searchClosure.setDelegate(searchMappingEntityConfig)
                searchClosure.setResolveStrategy(Closure.DELEGATE_FIRST)
                searchClosure.call()

                // TODO this was getting descriptors not the programmatic mapping builder
                if (searchMappingEntityConfig.indexedPropertyNames) {
                    indexedPropertiesByName[domainClass.getName()] = searchMappingEntityConfig.indexedPropertyNames
                }
            } else if (AnnotationUtils.isAnnotationDeclaredLocally(Indexed, domainClass.clazz)) {
                pluginLogger.info '* {} is indexed using annotations', domainClass.name
            }
        }

        log.debug('Registering indexed properties to Grails config key {}', INDEXED_ENTITIES_GRAILS_APP_CONFIG_KEY)
        log.trace('Indexed properties {}', indexedPropertiesByName)
        grailsApplication.config[INDEXED_ENTITIES_GRAILS_APP_CONFIG_KEY] = indexedPropertiesByName

    }
}
