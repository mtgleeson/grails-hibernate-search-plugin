package grails.plugins.hibernate.search.component

import org.hibernate.search.engine.search.common.BooleanOperator
import org.hibernate.search.engine.search.predicate.SearchPredicate
import org.hibernate.search.engine.search.predicate.dsl.SearchPredicateFactory
import org.hibernate.search.engine.search.predicate.dsl.SimpleQueryStringPredicateFieldMoreStep
import org.hibernate.search.engine.search.predicate.dsl.SimpleQueryStringPredicateFieldStep
import org.hibernate.search.engine.search.predicate.dsl.SimpleQueryStringPredicateOptionsStep
import org.hibernate.search.mapper.orm.scope.SearchScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * @since 14/05/2018
 */
abstract class Component {

    protected SearchScope searchScope

    Component parent
    List<Component> children = []

    SearchPredicateFactory getPredicateBuilder() {
        searchScope.predicate()
    }

    abstract SearchPredicate createSearchPredicate()

    def leftShift(component) {
        assert component instanceof Component: "'component' should be an instance of Component"
        component.parent = this
        children << component
    }

    Logger getLog() {
        LoggerFactory.getLogger(getClass())
    }

    String toString(int indent) {
        [('-' * indent) + this.class.simpleName, children.collect {it.toString(indent + 1)}].flatten().findAll {it}.join('\n')
    }

    @Override
    String toString() {
        toString(0)
    }
}


class SimpleQueryStringComponent extends Component {

    public static final Float DEFAULT_BOOST = 1f

    String field
    Float fieldBoost
    List<String> fields
    Float fieldsBoost
    Map<String, Float> fieldsAndBoost
    String queryString
    Boolean withAndAsDefaultOperator = false

    @Override
    SearchPredicate createSearchPredicate() {

        SimpleQueryStringPredicateFieldStep fieldStep = predicateBuilder.simpleQueryString()
        SimpleQueryStringPredicateFieldMoreStep nextStep

        // Handle individual field boosting
        if (fieldsAndBoost) {

            // Get the first field and boost as the context is different for this one
            field = fieldsAndBoost.keySet().first()
            fieldBoost = fieldsAndBoost.remove(field)

            nextStep = fieldStep.field(field).boost(fieldBoost)

            fieldsAndBoost.each {f, b ->
                nextStep = nextStep.field(f).boost(b)
            }
        } else {

            nextStep = fieldStep.field(field).boost(fieldBoost ?: DEFAULT_BOOST)

            if (fields) {
                nextStep = nextStep.fields(fields.toArray() as String[]).boost(fieldsBoost ?: DEFAULT_BOOST)
            }
        }

        SimpleQueryStringPredicateOptionsStep optionsStep = nextStep.matching(queryString)

        if (withAndAsDefaultOperator) optionsStep = optionsStep.defaultOperator(BooleanOperator.AND)

        log.debug('Adding SimpleQueryString for [{}]', queryString)
        optionsStep.toPredicate()
    }
}