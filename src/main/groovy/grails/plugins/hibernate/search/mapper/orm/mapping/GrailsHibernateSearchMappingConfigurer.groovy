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
import grails.plugins.hibernate.search.config.SearchMappingGlobalConfig
import grails.util.Holders
import groovy.util.logging.Slf4j
import org.grails.core.artefact.DomainClassArtefactHandler
import org.grails.datastore.mapping.reflect.ClassPropertyFetcher
import org.hibernate.search.mapper.orm.mapping.HibernateOrmMappingConfigurationContext
import org.hibernate.search.mapper.orm.mapping.HibernateOrmSearchMappingConfigurer
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.ProgrammaticMappingConfigurationContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.AnnotationUtils

import static grails.plugins.hibernate.search.HibernateSearchGrailsPlugin.INDEXED_ENTITIES_GRAILS_APP_CONFIG_KEY

/**
 * @since 11/10/2021
 */
@Slf4j
class GrailsHibernateSearchMappingConfigurer implements HibernateOrmSearchMappingConfigurer {

    private final static Logger pluginLogger = LoggerFactory.getLogger(HibernateSearchGrailsPlugin)

    GrailsApplication grailsApplication
    private final Map<String, List<String>> indexedPropertiesByName

    GrailsHibernateSearchMappingConfigurer() {
        indexedPropertiesByName = [:]
        grailsApplication = Holders.getGrailsApplication()
    }

    @Override
    void configure(HibernateOrmMappingConfigurationContext context) {

        configureGlobal(context)

        configureEntities(context)
    }

    /**
     * This is a temporary migration assistant to attempt to handle existing global config.
     * We cannot handle the analyzers or normalisers as these are now specificly handled by the Lucene or ElasticSearch configurer and there
     * is no way to know what the user has programmed.
     *
     * The idea is to throw warnings into the logs rather than compile time errors to start with
     * @param context
     */
    void configureGlobal(HibernateOrmMappingConfigurationContext context) {
        Object hibernateSearchConfig = grailsApplication.config.grails.plugins.hibernatesearch

        if (hibernateSearchConfig && hibernateSearchConfig instanceof Closure) {

            log.warn('DEPRECATED: Global config has been deprecated in favour of the XxxxAnalysisConfigurer. ' +
                     'See https://docs.jboss.org/hibernate/search/6.0/migration/html_single/#analysis-definition-provider for analyzers and normalisers.' +
                     'See https://docs.jboss.org/hibernate/search/6.0/migration/html_single/#full-text-filter for full-text-filters')
            SearchMappingGlobalConfig searchMappingGlobalConfig = new SearchMappingGlobalConfig()

            Closure hibernateSearchConfigClosure = (Closure) hibernateSearchConfig
            hibernateSearchConfigClosure.setDelegate(searchMappingGlobalConfig)
            hibernateSearchConfigClosure.setResolveStrategy(Closure.DELEGATE_FIRST)
            hibernateSearchConfigClosure.call()
        }
    }

    void configureEntities(HibernateOrmMappingConfigurationContext context) {

        log.debug('Configuring Hibernate Search entities')
        ProgrammaticMappingConfigurationContext programmaticMappingConfigurationContext = context.programmaticMapping()

        Collection<GrailsClass> domainClasses = grailsApplication.getArtefacts(DomainClassArtefactHandler.TYPE)

        List<DomainClassDependency> dependencyGraph = buildDependencyGraph(domainClasses)

        for (DomainClassDependency dcd : dependencyGraph) {
            configureDomainClassDependency(dcd, programmaticMappingConfigurationContext)
        }

        log.debug('Registering indexed properties to Grails config key {}', INDEXED_ENTITIES_GRAILS_APP_CONFIG_KEY)
        log.trace('Indexed properties {}', indexedPropertiesByName)
        grailsApplication.config[INDEXED_ENTITIES_GRAILS_APP_CONFIG_KEY] = indexedPropertiesByName
    }

    void configureDomainClassDependency(DomainClassDependency domainClassDependency,
                                        ProgrammaticMappingConfigurationContext programmaticMappingConfigurationContext,
                                        List<String> parentFields = []) {
        GrailsClass domainClass = domainClassDependency.domainClass
        List<String> indexedFields = configureEntity(domainClass, programmaticMappingConfigurationContext, parentFields)
        for (DomainClassDependency child : domainClassDependency.dependencies) {
            configureDomainClassDependency(child, programmaticMappingConfigurationContext, indexedFields)
        }
    }

    List<String> configureEntity(GrailsClass domainClass, ProgrammaticMappingConfigurationContext programmaticMappingConfigurationContext, List<String> parentFields) {
        Closure searchClosure = ClassPropertyFetcher.forClass(domainClass.getClazz()).getStaticPropertyValue(SearchMappingEntityConfig.INDEX_CONFIG_NAME, Closure)
        List<String> indexedFields = new ArrayList<>(parentFields)
        if (searchClosure != null) {
            pluginLogger.info '* {} is indexed', domainClass.name

            SearchMappingEntityConfig searchMappingEntityConfig = new SearchMappingEntityConfig(programmaticMappingConfigurationContext, domainClass, parentFields)

            searchClosure.setDelegate(searchMappingEntityConfig)
            searchClosure.setResolveStrategy(Closure.DELEGATE_FIRST)
            searchClosure.call()

            if (searchMappingEntityConfig.indexedPropertyNames) {
                indexedFields.addAll(searchMappingEntityConfig.indexedPropertyNames)
                indexedPropertiesByName[domainClass.getName()] = indexedFields
            }
        } else if (AnnotationUtils.isAnnotationDeclaredLocally(Indexed, domainClass.clazz)) {
            pluginLogger.info '* {} is indexed using annotations', domainClass.name
        }
        indexedFields
    }

    List<DomainClassDependency> buildDependencyGraph(Collection<GrailsClass> domainClasses) {

        Collection<GrailsClass> indexedDomainClasses = domainClasses.findAll {domainClass ->
            ClassPropertyFetcher.forClass(domainClass.getClazz()).getStaticPropertyValue(SearchMappingEntityConfig.INDEX_CONFIG_NAME, Closure) ||
            AnnotationUtils.isAnnotationDeclaredLocally(Indexed, domainClass.clazz)
        }

        Collection<Class> indexedClasses = indexedDomainClasses.collect {it.clazz}

        List<DomainClassDependency> rootDependencies = indexedDomainClasses
            .findAll {!(it.clazz.superclass in indexedClasses)}
            .collect {new DomainClassDependency(it)}

        if (rootDependencies.size() != indexedClasses.size()) {
            Collection<GrailsClass> nonRootGrailsClasses = indexedDomainClasses.findAll {it.clazz.superclass in indexedClasses}
            addNonRootGrailsClassesToRoots(rootDependencies, nonRootGrailsClasses)
        }

        log.debug('Inheritance Graph:\n{}', rootDependencies.join('\n'))
        rootDependencies
    }

    void addNonRootGrailsClassesToRoots(List<DomainClassDependency> rootDependencies, Collection<GrailsClass> nonRootGrailsClasses) {
        Collection<Class> indexedClasses = rootDependencies.collect {it.domainClass.clazz}

        List<DomainClassDependency> newRootDependencies = nonRootGrailsClasses
            .findAll {it.clazz.superclass in indexedClasses}
            .collect {gc ->
                DomainClassDependency domainClassDependency = new DomainClassDependency(gc)
                rootDependencies.find {it.domainClass.clazz == gc.clazz.superclass} << domainClassDependency
                domainClassDependency
            }

        Collection<GrailsClass> newNonRootGrailsClasses = nonRootGrailsClasses.findAll {!(it.clazz.superclass in indexedClasses)}
        if (newNonRootGrailsClasses) addNonRootGrailsClassesToRoots(newRootDependencies, newNonRootGrailsClasses)
    }


    class DomainClassDependency {
        GrailsClass domainClass
        List<DomainClassDependency> dependencies = []

        DomainClassDependency(GrailsClass domainClass) {
            this.domainClass = domainClass
        }

        def leftShift(DomainClassDependency domainClassDependency) {
            dependencies << domainClassDependency
        }

        String toString(int n = 0) {
            StringBuilder sb = new StringBuilder(' ' * n)
            sb.append(domainClass.name)
            if (dependencies) {
                sb.append('\n')
                sb.append(dependencies.collect {it.toString(n + 2)}.join('\n'))
            }
            sb.toString()
        }
    }
}
