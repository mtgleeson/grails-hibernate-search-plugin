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
import org.hibernate.search.engine.backend.types.Norms
import org.hibernate.search.engine.backend.types.Projectable
import org.hibernate.search.engine.backend.types.Searchable
import org.hibernate.search.engine.backend.types.Sortable
import org.hibernate.search.engine.backend.types.TermVector
import org.hibernate.search.mapper.pojo.bridge.mapping.programmatic.PropertyBinder
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.ProgrammaticMappingConfigurationContext
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.PropertyMappingFullTextFieldOptionsStep
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.PropertyMappingGenericFieldOptionsStep
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.PropertyMappingIndexedEmbeddedStep
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.PropertyMappingKeywordFieldOptionsStep
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.PropertyMappingNonFullTextFieldOptionsStep
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.PropertyMappingStandardFieldOptionsStep
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.PropertyMappingStep
import org.hibernate.search.mapper.pojo.mapping.definition.programmatic.impl.TypeMappingStepImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.lang.reflect.Field

@SuppressWarnings('GroovyUnusedDeclaration')
class SearchMappingEntityConfig {

    public static final String INDEX_CONFIG_NAME = 'search'

    private final static Logger log = LoggerFactory.getLogger(SearchMappingEntityConfig)

    private static final String IDENTITY = 'id'

    private Object analyzer
    private final GrailsClass domainClass
    private final TypeMappingStepImpl typeMappingStep
    private final List<String> indexedPropertyNames
    private final List<String> parentIndexedFields
    private final ProgrammaticMappingConfigurationContext mapping

    SearchMappingEntityConfig(ProgrammaticMappingConfigurationContext mapping, GrailsClass domainClass, List<String> parentIndexedFields) {
        this.indexedPropertyNames = []
        this.domainClass = domainClass
        this.typeMappingStep = mapping.type(domainClass.clazz) as TypeMappingStepImpl
        this.parentIndexedFields = parentIndexedFields
        this.mapping = mapping

        // Only index non-abstract classes
        // If there are any parent indexed fields then the entity is already marked as indexed as it is a subclass
        if (!domainClass.isAbstract() && !parentIndexedFields) {
            typeMappingStep.indexed()

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

    Object invokeMethod(String name, argsAsList) {

        Map args = argsAsList[0] as Map ?: [:]

        Field backingField = findField(name)

        if (!backingField) {
            log.warn 'Indexed property not found! name={} entity={}', name, domainClass
            return
        }

        if (name in parentIndexedFields) {
            log.info('Cannot add property [{}] is already indexed by a superclass', name)
            return
        }

        String propertyName = backingField.getName()
        String fieldName = args.name ?: name
        Class fieldType = backingField.type

        log.debug 'Property {}.{} found with field name [{}] to be indexed', domainClass.clazz.canonicalName, propertyName, fieldName
        indexedPropertyNames.add(name)

        PropertyMappingStep propertyMappingStep = typeMappingStep.property(fieldName)

        // Issue exists when the backingfield is inside a trait as the property name is then long format and wont match up
        // Unable to resolve path 'xxxx__yyyy' to a persisted attribute in Hibernate ORM metadata
        // org.hibernate.search.mapper.orm.model.impl.HibernateOrmClassRawTypeModel.findPropertyMember
        // PR open https://github.com/hibernate/hibernate-search/pull/2686
        if (propertyName != fieldName) {
            log.warn('Conflicting indexing on property [{}] with field name [{}]. ' +
                     'This will produce an orm mapping issue without HSEARCH-4348 fixed.', propertyName, fieldName)
        }

        if (args.indexEmbedded) {

            log.debug '> Adding indexEmbedded property'

            PropertyMappingIndexedEmbeddedStep indexEmbeddedMapping = propertyMappingStep.indexedEmbedded()

            if (args.indexEmbedded instanceof Map) {

                Integer depth = args.indexEmbedded.depth as Integer
                if (depth) {
                    indexEmbeddedMapping.includeDepth(depth)
                }

                Boolean includeEmbeddedObjectId = args.indexEmbedded.includeEmbeddedObjectId?.toBoolean()
                if (includeEmbeddedObjectId) {
                    indexEmbeddedMapping.includeEmbeddedObjectId(includeEmbeddedObjectId)
                }
            }
        } else if (args.containedIn) {
            log.warn '> Ignoring containedIn property as now removed in Hibernate Search 6'
        } else {
            log.debug '> Adding indexed property'
            configureFieldMapping(propertyMappingStep, fieldName, fieldType, args)
        }
    }

    private Field findField(String name) {
        Field backingField = null

        // try to find the field in the parent class hierarchy (starting from domain class itself)
        Class clazz = domainClass.clazz
        while (clazz) {
            try {
                backingField = clazz.getDeclaredField(name)
                break
            } catch (NoSuchFieldException ignored) {
                // and in groovy's traits
                backingField = clazz.getDeclaredFields().find {field -> field.getName().endsWith('__' + name)}
                if (backingField) {
                    break
                }
                clazz = clazz.getSuperclass()
            }
        }

        backingField
    }

    private void configureFieldMapping(PropertyMappingStep propertyMappingStep, String fieldName, Class fieldType, Map<String, Object> args) {

        PropertyMappingStandardFieldOptionsStep fieldOptionsStep = createFieldOptionsStep(propertyMappingStep, fieldName, fieldType, args)

        if (args.index) {
            logUpdatedPropertyWarning(fieldName, 'index', 'searchable')
            fieldOptionsStep.searchable(Searchable.valueOf((args.index as String).toUpperCase()))
        }
        if (args.searchable) {
            fieldOptionsStep.searchable(Searchable.valueOf((args.searchable as String).toUpperCase()))
        }


        if (args.store) {
            logUpdatedPropertyWarning(fieldName, 'store', 'projectable')
            fieldOptionsStep.projectable(Projectable.valueOf((args.store as String).toUpperCase()))
        }
        if (args.projectable) {
            fieldOptionsStep.projectable(Projectable.valueOf((args.projectable as String).toUpperCase()))
        }

        if (args.termVector && fieldOptionsStep instanceof PropertyMappingFullTextFieldOptionsStep) {
            fieldOptionsStep.termVector(TermVector.valueOf((args.termVector as String).toUpperCase()))
        }

        if (args.norms) {
            if (fieldOptionsStep instanceof PropertyMappingFullTextFieldOptionsStep) {
                fieldOptionsStep.norms(Norms.valueOf((args.norms as String).toUpperCase()))
            }
            if (fieldOptionsStep instanceof PropertyMappingKeywordFieldOptionsStep) {
                fieldOptionsStep.norms(Norms.valueOf((args.norms as String).toUpperCase()))
            }
        }
        if (args.binder) {
            def binder = args.binder
            if (binder instanceof PropertyBinder) {
                fieldOptionsStep.binder(binder)
            } else if (binder instanceof Class<PropertyBinder>) {
                fieldOptionsStep.binder((binder as Class<PropertyBinder>).getDeclaredConstructor().newInstance())
            } else if (binder instanceof Map) {
                if (binder.class instanceof Class<PropertyBinder>) {
                    Class[] paramTypes = [] as Class[]
                    Object[] instArgs = [] as Object[]
                    if (binder.args) {
                        instArgs = binder.args
                        paramTypes = binder.args.collect {it.class}.toArray(paramTypes)
                    }
                    fieldOptionsStep.binder((binder.class as Class<PropertyBinder>).getDeclaredConstructor(paramTypes).newInstance(instArgs))
                }
            }
        }

        if (args.numeric) {
            logUpdatedPropertyWarning(fieldName, 'numeric', 'removed')
        }

        if (args.boost) {
            logUpdatedPropertyWarning(fieldName, 'boost', 'removed')
        }

        if (args.date) {
            logUpdatedPropertyWarning(fieldName, 'date', 'removed')
        }

        if (args.bridge) {
            fieldOptionsStep.valueBridge(args.bridge['class'] as Class)
            logUpdatedPropertyWarning(fieldName, 'bridge.params', 'use ValueBridges or PropertyBinders instead')
        }
        if (args.sortable) {
            implementSortable(propertyMappingStep, fieldOptionsStep, fieldName, args.sortable)
        }
    }

    private PropertyMappingStandardFieldOptionsStep createFieldOptionsStep(PropertyMappingStep propertyMappingStep, String fieldName, Class fieldType,
                                                                           Map<String, Object> args) {
        PropertyMappingStandardFieldOptionsStep fieldOptionsStep
        log.debug('Configuring indexing for field {} of type {}', fieldName, fieldType)

        if (fieldType in [String, Character] || Enum.isAssignableFrom(fieldType)) {


            if (args.containsKey('analyze') && !args.analyse) {
                fieldOptionsStep = propertyMappingStep.keywordField(fieldName)
            } else if (args.normalizer) {
                fieldOptionsStep = propertyMappingStep.keywordField(fieldName)
                if (args.normalizer instanceof Class) {
                    logUpdatedPropertyWarning(fieldName, 'normalizer', 'only accept names not classes')
                } else {
                    fieldOptionsStep.normalizer(args.normalizer as String)
                }
            } else {
                fieldOptionsStep = propertyMappingStep.fullTextField(fieldName)
                if (args.analyzer) {
                    if (args.analyzer instanceof Class) {
                        logUpdatedPropertyWarning(fieldName, 'analyzer', 'only accept names not classes')
                    } else {
                        fieldOptionsStep.analyzer(args.analyzer as String)
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
        } else {
            fieldOptionsStep = propertyMappingStep.genericField(fieldName)
        }
        fieldOptionsStep
    }

    private void implementSortable(PropertyMappingStep propertyMappingStep, PropertyMappingStandardFieldOptionsStep fieldOptionsStep, String fieldName, def args) {
        PropertyMappingNonFullTextFieldOptionsStep sortableField

        Map mapArgs = args instanceof Map ? args : [:]
        String sortableFieldName = mapArgs.name ? mapArgs.name : "${fieldName}_sort"
        log.debug('> Adding sortable field {}', sortableFieldName)
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

    private static void logUpdatedPropertyWarning(String field, String deprecatedProperty, String updatedProperty) {
        if (updatedProperty != 'removed') {
            log.warn('DEPRECATED: The field [{}] has been marked with search property [{}], this should be updated to [{}]', field, deprecatedProperty, updatedProperty)
        } else {
            log.warn('DEPRECATED: The field [{}] has been marked with search property [{}], this has been removed', field, deprecatedProperty)
        }
    }
}
