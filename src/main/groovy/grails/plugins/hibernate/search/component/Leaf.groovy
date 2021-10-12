package grails.plugins.hibernate.search.component

import org.hibernate.search.engine.search.common.ValueConvert
import org.hibernate.search.engine.search.predicate.SearchPredicate
import org.hibernate.search.engine.search.predicate.dsl.MatchPredicateOptionsStep
import org.hibernate.search.engine.search.predicate.dsl.PhrasePredicateOptionsStep
import org.hibernate.search.engine.search.predicate.dsl.PredicateFinalStep
import org.hibernate.search.engine.search.predicate.dsl.PredicateScoreStep
import org.hibernate.search.engine.search.predicate.dsl.RangePredicateOptionsStep
import org.hibernate.search.engine.search.predicate.dsl.WildcardPredicateOptionsStep

abstract class Leaf<K extends PredicateScoreStep> extends Composite {

    String field
    Boolean ignoreAnalyzer = false
    Boolean ignoreFieldBridge = false
    float boostedTo

    abstract K createPredicateScoreStep()

    @Override
    def leftShift(component) {
        throw new UnsupportedOperationException("${this.class.name} is a leaf")
    }

    @Override
    SearchPredicate createSearchPredicate() {
        checkBoost(createPredicateScoreStep()).toPredicate()
    }

    MatchPredicateOptionsStep checkSkipAnalysis(MatchPredicateOptionsStep step) {
        ignoreAnalyzer ? step.skipAnalysis() : step
    }

    PredicateFinalStep checkBoost(K step) {
        (boostedTo ? step.boost(boostedTo) : step) as PredicateFinalStep
    }

    ValueConvert getValueConvert() {
        ignoreFieldBridge ? ValueConvert.NO : ValueConvert.YES
    }
}

class BelowComponent extends Leaf<RangePredicateOptionsStep> {
    def below

    RangePredicateOptionsStep createPredicateScoreStep() {
        getPredicateBuilder().range().field(field).atMost(below)
    }
}

class BetweenComponent extends Leaf<RangePredicateOptionsStep> {
    def from
    def to

    RangePredicateOptionsStep createPredicateScoreStep() {
        getPredicateBuilder().range().field(field).between(from, to)
    }
}

class AboveComponent extends Leaf<RangePredicateOptionsStep> {
    def above

    RangePredicateOptionsStep createPredicateScoreStep() {
        getPredicateBuilder().range().field(field).atLeast(above)
    }
}

class KeywordComponent extends Leaf<MatchPredicateOptionsStep> {
    def matching

    MatchPredicateOptionsStep createPredicateScoreStep() {
        checkSkipAnalysis getPredicateBuilder().match().field(field).matching(matching, getValueConvert())
    }
}

class FuzzyComponent extends Leaf<MatchPredicateOptionsStep> {
    def matching

    int prefixLength
    int maxDistance

    @Deprecated
    float threshold

    MatchPredicateOptionsStep createPredicateScoreStep() {
        MatchPredicateOptionsStep step = getPredicateBuilder().match().field(field).matching(matching, getValueConvert())

        if (maxDistance) {
            if (prefixLength) step = step.fuzzy(maxDistance, prefixLength)
            else step = step.fuzzy(maxDistance)
        }

        if (threshold) {
            log.warn('DEPRECATED : [threshold] has been removed from the fuzzy search functionality')
        }
        checkSkipAnalysis step
    }
}

class WildcardComponent extends Leaf<WildcardPredicateOptionsStep> {
    String matching

    WildcardPredicateOptionsStep createPredicateScoreStep() {
        getPredicateBuilder().wildcard().field(field).matching(matching)
    }
}

class PhraseComponent extends Leaf<PhrasePredicateOptionsStep> {
    String sentence

    PhrasePredicateOptionsStep createPredicateScoreStep() {
        getPredicateBuilder().phrase().field(field).matching(sentence)
    }
}