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
package grails.plugins.hibernate.search.config

import grails.core.GrailsClass
import grails.plugins.hibernate.search.HibernateSearchGrailsPlugin
import groovy.util.logging.Slf4j
import org.hibernate.search.engine.backend.types.Norms
import org.hibernate.search.engine.backend.types.Projectable
import org.hibernate.search.engine.backend.types.Searchable
import org.hibernate.search.engine.backend.types.Sortable
import org.hibernate.search.engine.backend.types.TermVector
import org.hibernate.search.mapper.pojo.automaticindexing.ReindexOnUpdate
import org.hibernate.search.mapper.pojo.bridge.mapping.programmatic.PropertyBinder
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.IndexingDependencyOptionsStep
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.ProgrammaticMappingConfigurationContext
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.PropertyMappingFullTextFieldOptionsStep
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.PropertyMappingGenericFieldOptionsStep
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.PropertyMappingIndexedEmbeddedStep
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.PropertyMappingKeywordFieldOptionsStep
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.PropertyMappingNonFullTextFieldOptionsStep
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.PropertyMappingStandardFieldOptionsStep
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.PropertyMappingStep
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.impl.TypeMappingStepImpl
import org.hibernate.search.mapper.pojo.model.path.PojoModelPath
import org.hibernate.search.mapper.pojo.model.path.PojoModelPathValueNode
import org.hibernate.search.mapper.pojo.model.spi.PojoGenericTypeModel
import org.hibernate.search.mapper.pojo.model.spi.PojoPropertyModel
import org.hibernate.search.mapper.pojo.model.spi.PojoRawTypeModel
import org.hibernate.search.util.common.SearchException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@Slf4j
@SuppressWarnings('GroovyUnusedDeclaration')
class SearchMappingEntityConfig {

    public static final String INDEX_CONFIG_NAME = 'search'

    private final static Logger PLUGIN_LOGGER = LoggerFactory.getLogger(HibernateSearchGrailsPlugin)

    private static final String IDENTITY = 'id'

    private Object analyzer
    private final GrailsClass domainClass
    private final TypeMappingStepImpl typeMappingStep
    private final List<String> indexedPropertyNames
    private final List<String> parentIndexedFields
    private final PojoRawTypeModel entityModel
    private final ProgrammaticMappingConfigurationContext mapping

    static Closure propertyMapping(@DelegatesTo(value = PropertyMappingStep, strategy = Closure.DELEGATE_ONLY) closure) {
        closure
    }

    static Closure additionalFieldOptionsMapping(@DelegatesTo(value = PropertyMappingStandardFieldOptionsStep, strategy = Closure.DELEGATE_ONLY) Closure closure) {
        closure
    }

    static Closure entityMapping(@DelegatesTo(value = SearchMappingEntityConfig, strategy = Closure.DELEGATE_ONLY) closure) {
        closure
    }

    SearchMappingEntityConfig(ProgrammaticMappingConfigurationContext mapping, GrailsClass domainClass, List<String> parentIndexedFields) {
        this.indexedPropertyNames = []
        this.domainClass = domainClass
        this.typeMappingStep = mapping.type(domainClass.clazz) as TypeMappingStepImpl
        this.entityModel = (typeMappingStep as TypeMappingStepImpl).getTypeModel()
        this.parentIndexedFields = parentIndexedFields
        this.mapping = mapping

        PLUGIN_LOGGER.info '* {} is indexed', domainClass.name
        typeMappingStep.indexed()
        // If there are any parent indexed fields then the entity is already marked as indexed as it is a subclass and we dont need to add the id fieldw
        if (!domainClass.isAbstract() && !parentIndexedFields) {
            // Add id property
            typeMappingStep.property(IDENTITY).keywordField().documentId()
        }
    }

    List<String> getIndexedPropertyNames() {
        return indexedPropertyNames
    }

    void setAnalyzer(Object analyzer) {
        this.analyzer = analyzer
    }

    def methodMissing(String name, def args) {

        PojoPropertyModel propertyModel = findPropertyModel(name)

        if (!propertyModel) {
            log.warn 'Indexed property not found! name={} entity={}', name, domainClass
            return
        }
        if (name in parentIndexedFields) {
            log.debug('Property [{}] is already indexed by a superclass', name)
            return
        }

        indexProperty(name, propertyModel, args)
    }

    private void indexProperty(String name, PojoPropertyModel propertyModel, def args) {
        String propertyName = propertyModel.name()
        log.debug 'Property [{}] found with name [{}] to be indexed', propertyName, name
        indexedPropertyNames.add(name)

        PropertyMappingStep propertyMappingStep = typeMappingStep.property(name)

        // Issue exists when the backingfield is inside a trait as the property name is then long format and wont match up
        // Unable to resolve path 'xxxx__yyyy' to a persisted attribute in Hibernate ORM metadata
        // org.hibernate.search.mapper.orm.model.impl.HibernateOrmClassRawTypeModel.findPropertyMember
        // PR open https://github.com/hibernate/hibernate-search/pull/2686
        if (propertyName != name) {
            log.warn('Conflicting indexing on property [{}] with field name [{}]. ' +
                     'This will produce an orm mapping issue without HSEARCH-4348 fixed.', propertyName, name)
        }

        def mappingArg = args[0]

        if (mappingArg instanceof Map) {
            indexPropertyFromMap(propertyMappingStep, name, propertyModel, mappingArg)
        } else if (mappingArg instanceof Closure) {
            indexPropertyFromClosure(propertyMappingStep, mappingArg)
        } else {
            log.warn('Unknown arg type {}', mappingArg.getClass())
        }
    }

    private void indexPropertyFromClosure(PropertyMappingStep propertyMappingStep, @DelegatesTo(PropertyMappingStep) Closure closure) {
        log.debug('  Indexing property from closure')
        closure.resolveStrategy = Closure.DELEGATE_ONLY
        closure.delegate = propertyMappingStep
        closure.call()
    }

    private void indexPropertyFromMap(PropertyMappingStep propertyMappingStep, String name, PojoPropertyModel propertyModel, Map<String, Object> argMap) {
        log.debug('  Indexing property from map')
        String fieldName = argMap.remove('name') ?: name
        migrateFieldMappings(fieldName, argMap)

        configurePropertyMappingStep(propertyMappingStep, fieldName, argMap)

        // As we empty the map as we go, if there are any options left over then they are fieldOptions
        if (argMap) {
            log.debug('  Adding fieldOptions [{}]', argMap)
            PropertyMappingStandardFieldOptionsStep fieldOptionsStep = createFieldOptionsStep(propertyMappingStep, fieldName, propertyModel.typeModel(), argMap)

            configureFieldMapping(fieldOptionsStep, fieldName, argMap)

            if (argMap.sortable) {
                implementSortable(propertyMappingStep, fieldOptionsStep, fieldName, argMap.remove('sortable'))
            }
        }

        if (argMap) log.warn('Unhandled indexing args left [{}]', argMap)
    }

    private static void configurePropertyMappingStep(PropertyMappingStep propertyMappingStep, String fieldName, Map<String, Object> args) {

        if (args.binder) {
            log.debug '  Adding binder'
            def binder = args.remove('binder')
            if (binder instanceof PropertyBinder) {
                propertyMappingStep.binder(binder)
            } else if (binder instanceof Class<PropertyBinder>) {
                propertyMappingStep.binder((binder as Class<PropertyBinder>).getDeclaredConstructor().newInstance())
            } else if (binder instanceof Map) {
                if (binder.class instanceof Class<PropertyBinder>) {
                    Class[] paramTypes = [] as Class[]
                    Object[] instArgs = [] as Object[]
                    if (binder.args) {
                        instArgs = binder.args
                        paramTypes = binder.args.collect {it.class}.toArray(paramTypes)
                    }
                    propertyMappingStep.binder((binder.class as Class<PropertyBinder>).getDeclaredConstructor(paramTypes).newInstance(instArgs))
                }
            }
        }

        if (args.indexEmbedded) {
            log.debug '  Adding indexEmbedded'
            def embeddedArgs = args.remove('indexEmbedded')
            if (embeddedArgs instanceof Map) {
                String embeddedName = embeddedArgs.name ?: fieldName
                PropertyMappingIndexedEmbeddedStep indexEmbeddedMapping = propertyMappingStep.indexedEmbedded(embeddedName)
                if (embeddedArgs.containsKey('depth')) {
                    indexEmbeddedMapping.includeDepth(embeddedArgs.depth as int)
                }
                if (embeddedArgs.containsKey('includeEmbeddedObjectId')) {
                    indexEmbeddedMapping.includeEmbeddedObjectId(embeddedArgs.includeEmbeddedObjectId as boolean)
                }
                if (embeddedArgs.containsKey('associationInverseSide')) {
                    PojoModelPathValueNode node = buildPojoModelPathValueNode(embeddedArgs.associationInverseSide)
                    if (node) {
                        indexEmbeddedMapping.associationInverseSide(node)
                    } else {
                        log.warn('Could not add associationInverseSide to field [{}] using args [{}]', fieldName, embeddedArgs.associationInverseSide)
                    }
                }
                if (embeddedArgs.containsKey('includePaths')) {
                    indexEmbeddedMapping.includePaths(embeddedArgs.includePaths)
                }
            } else {
                propertyMappingStep.indexedEmbedded()
            }
        }

        if (args.indexingDependency) {
            log.debug '  Adding indexingDependency'
            Map indexingDependency = args.remove('indexingDependency')
            IndexingDependencyOptionsStep indexingDependencyOptionsStep = propertyMappingStep.indexingDependency()
            if (indexingDependency.derivedFrom) {
                indexingDependencyOptionsStep.derivedFrom(buildPojoModelPathValueNode(indexingDependency.derivedFrom))
            }
            if (indexingDependency.reindexOnUpdate) {
                indexingDependencyOptionsStep.reindexOnUpdate(ReindexOnUpdate.valueOf((indexingDependency.reindexOnUpdate as String).toUpperCase()))
            }
        }
    }

    @SuppressWarnings('GroovyInArgumentCheck')
    private PropertyMappingStandardFieldOptionsStep createFieldOptionsStep(PropertyMappingStep propertyMappingStep,
                                                                           String fieldName,
                                                                           PojoGenericTypeModel fieldType,
                                                                           Map<String, Object> args) {
        PropertyMappingStandardFieldOptionsStep fieldOptionsStep

        Class fieldClass = getFieldTypeClass(fieldType)

        if (fieldClass in [String, Character] || Enum.isAssignableFrom(fieldClass)) {

            boolean analyseField = true
            if (args.containsKey('analyze')) {
                analyseField = args.remove('analyze')
            }
            if (!analyseField) {
                log.debug('  Creating keywordField index [{}]', fieldName)
                fieldOptionsStep = propertyMappingStep.keywordField(fieldName)
            } else {
                log.debug('  Creating fullTextField index [{}]', fieldName)
                fieldOptionsStep = propertyMappingStep.fullTextField(fieldName)
            }
        } else {
            log.debug('  Creating genericField index [{}]', fieldName)
            fieldOptionsStep = propertyMappingStep.genericField(fieldName)
        }
        fieldOptionsStep
    }

    private void configureFieldMapping(PropertyMappingStandardFieldOptionsStep fieldOptionsStep, String fieldName, Map<String, Object> args) {

        if (args.searchable) {
            fieldOptionsStep.searchable(Searchable.valueOf((args.remove('searchable') as String).toUpperCase()))
        }

        if (args.projectable) {
            fieldOptionsStep.projectable(Projectable.valueOf((args.remove('projectable') as String).toUpperCase()))
        }

        if (args.norms) {
            if (fieldOptionsStep instanceof PropertyMappingFullTextFieldOptionsStep) {
                fieldOptionsStep.norms(Norms.valueOf((args.remove('norms') as String).toUpperCase()))
            }
            if (fieldOptionsStep instanceof PropertyMappingKeywordFieldOptionsStep) {
                fieldOptionsStep.norms(Norms.valueOf((args.remove('norms') as String).toUpperCase()))
            }
        }

        if (args.bridge) {
            fieldOptionsStep.valueBridge(args.remove('bridge'))
        }

        if (fieldOptionsStep instanceof PropertyMappingFullTextFieldOptionsStep) {
            if (args.termVector) {
                fieldOptionsStep.termVector(TermVector.valueOf((args.remove('termVector') as String).toUpperCase()))
            }
            if (args.analyzer) {
                def argsAnalyzer = args.remove('analyzer')
                if (argsAnalyzer instanceof Class) {
                    logUpdatedPropertyWarning(fieldName, 'analyzer', 'only accept names not classes')
                } else {
                    fieldOptionsStep.analyzer(argsAnalyzer as String)
                }
            }
            if (analyzer) {
                if (analyzer instanceof Class) {
                    logUpdatedPropertyWarning(fieldName, 'analyzer', 'only accept names not classes')
                } else {
                    fieldOptionsStep.analyzer(analyzer as String)
                }
            }
        }
        if (fieldOptionsStep instanceof PropertyMappingKeywordFieldOptionsStep) {
            if (args.normalizer) {
                def argsNormalizer = args.remove('normalizer')
                if (argsNormalizer instanceof Class) {
                    logUpdatedPropertyWarning(fieldName, 'normalizer', 'only accept names not classes')
                } else {
                    fieldOptionsStep.normalizer(argsNormalizer as String)
                }
            }
        }

        if (args.additionalFieldOptionsMapping) {
            Closure additionalFieldOptionsMapping = args.remove('additionalFieldOptionsMapping')
            applyAdditionalFieldOptionsMapping(fieldOptionsStep, additionalFieldOptionsMapping)
        }
    }

    private static void applyAdditionalFieldOptionsMapping(PropertyMappingStandardFieldOptionsStep fieldOptionsStep,
                                                           @DelegatesTo(PropertyMappingStandardFieldOptionsStep) Closure customMapping) {
        customMapping.delegate = fieldOptionsStep
        customMapping.resolveStrategy = Closure.DELEGATE_ONLY
        customMapping.call()
    }

    private static void implementSortable(PropertyMappingStep propertyMappingStep, PropertyMappingStandardFieldOptionsStep fieldOptionsStep, String fieldName, def args) {
        PropertyMappingNonFullTextFieldOptionsStep sortableField

        Map mapArgs = args instanceof Map ? args : [:]
        String sortableFieldName = mapArgs.name ? mapArgs.name : "${fieldName}_sort"
        log.debug('  Adding sortable field [{}]', sortableFieldName)
        if (fieldOptionsStep instanceof PropertyMappingKeywordFieldOptionsStep) {
            sortableField = mapArgs.name ? propertyMappingStep.keywordField(sortableFieldName) : fieldOptionsStep
        } else if (fieldOptionsStep instanceof PropertyMappingGenericFieldOptionsStep) {
            sortableField = mapArgs.name ? propertyMappingStep.genericField(sortableFieldName) : fieldOptionsStep
        } else {
            sortableField = propertyMappingStep.keywordField(sortableFieldName)
        }

        sortableField.sortable(Sortable.YES)

        if (sortableField instanceof PropertyMappingKeywordFieldOptionsStep && mapArgs.normalizer) {
            if (mapArgs.normalizer instanceof Class) {
                logUpdatedPropertyWarning(sortableFieldName, 'normalizer', 'only accept names not classes')
            } else {
                sortableField.normalizer(mapArgs.normalizer as String)
            }
        }
    }

    private PojoPropertyModel findPropertyModel(String name) {
        try {
            entityModel.property(name)
        } catch (SearchException ex) {
            log.warn(ex.message)
            null
        }
    }

    private Class getFieldTypeClass(PojoGenericTypeModel fieldType) {
        try {
            getClass().classLoader.loadClass(fieldType.name())
        } catch (ClassNotFoundException ignored) {
            getClass().classLoader.loadClass(fieldType.rawType().name())
        }
    }

    private static PojoModelPathValueNode buildPojoModelPathValueNode(def args) {
        if (args instanceof String) {
            return PojoModelPath.builder().property(args).toValuePathOrNull()
        }
        if (args instanceof PojoModelPathValueNode) return args
        if (args instanceof Iterable<String>) {
            PojoModelPath.Builder builder = PojoModelPath.builder()
            args.each {builder.property(it)}
            return builder.toValuePathOrNull()
        }

        Map map = args as Map
        PojoModelPath.Builder builder = PojoModelPath.builder()
        if (map.property) builder.property(map.property)
        if (map.value) builder.value(map.value)
        builder.toValuePathOrNull()
    }

    private static migrateFieldMappings(String fieldName, Map argMap) {
        if (argMap.containedIn) {
            argMap.remove('containedIn')
            logUpdatedPropertyWarning(fieldName, 'containedId', null)
        }

        if (argMap.numeric) {
            argMap.remove('numeric')
            logUpdatedPropertyWarning(fieldName, 'numeric', 'removed')
        }

        if (argMap.boost) {
            argMap.remove('boost')
            logUpdatedPropertyWarning(fieldName, 'boost', 'removed')
        }

        if (argMap.date) {
            argMap.remove('date')
            logUpdatedPropertyWarning(fieldName, 'date', 'removed')
        }


        if (argMap.index) {
            logUpdatedPropertyWarning(fieldName, 'index', 'searchable')
            argMap.searchable = argMap.searchable ?: argMap.remove('index')
        }

        if (argMap.store) {
            logUpdatedPropertyWarning(fieldName, 'store', 'projectable')
            argMap.projectable = argMap.projectable ?: argMap.remove('store')
        }

        if (argMap.bridge && argMap.bridge instanceof Map) {
            Map bridgeMap = argMap.bridge as Map
            logUpdatedPropertyWarning(fieldName, 'bridge as Map', 'use ValueBridges or PropertyBinders instead')
            argMap.bridge = bridgeMap['class']
        }
    }

    private static void logUpdatedPropertyWarning(String field, String deprecatedProperty, String updatedProperty) {
        if (updatedProperty != 'removed') {
            log.warn('DEPRECATED: The field [{}] has been marked with search property [{}], this should be updated to [{}]', field, deprecatedProperty, updatedProperty)
        } else {
            log.warn('DEPRECATED: The field [{}] has been marked with search property [{}], this has been removed', field, deprecatedProperty)
        }
    }
}
