package grails.plugins.hibernate.search.component


import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import org.hibernate.search.engine.search.predicate.SearchPredicate
import org.hibernate.search.engine.search.predicate.dsl.BooleanPredicateClausesStep

abstract class Composite extends Component {

    /**
     * @return true if composite contains at least one valid (not empty) query
     */
    protected boolean forEachQuery(@DelegatesTo(SearchPredicate)
                                   @ClosureParams(value = SimpleType, options = 'org.hibernate.search.engine.search.predicate.SearchPredicate') Closure action) {

        boolean notEmpty = false
        if (children) {
            for (child in children) {
                SearchPredicate subQuery = child.createSearchPredicate()
                action.delegate = subQuery
                action.resolveStrategy = Closure.DELEGATE_FIRST
                action.call(subQuery)
                notEmpty = true
            }
        }
        notEmpty
    }
}

class MustNotComponent extends Composite {
    SearchPredicate createSearchPredicate() {
        BooleanPredicateClausesStep booleanStep = predicateBuilder.bool()

        boolean notEmpty = forEachQuery {subQuery ->
            booleanStep = booleanStep.mustNot(subQuery)
        }

        notEmpty ? booleanStep.toPredicate() : predicateBuilder.matchAll().toPredicate()
    }
}

class MustComponent extends Composite {
    SearchPredicate createSearchPredicate() {
        BooleanPredicateClausesStep booleanStep = predicateBuilder.bool()
        boolean notEmpty = forEachQuery {subQuery ->
            booleanStep = booleanStep.must(subQuery)
        }

        notEmpty ? booleanStep.toPredicate() : predicateBuilder.matchAll().toPredicate()
    }
}

class ShouldComponent extends Composite {
    SearchPredicate createSearchPredicate() {
        BooleanPredicateClausesStep booleanStep = predicateBuilder.bool()
        boolean notEmpty = forEachQuery {subQuery ->
            booleanStep = booleanStep.should(subQuery)
        }

        notEmpty ? booleanStep.toPredicate() : predicateBuilder.matchAll().toPredicate()
    }
}