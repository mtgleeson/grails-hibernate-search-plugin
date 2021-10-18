/* Copyright 2011 the original author or authors.
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
package grails.plugins.hibernate.search.config


import groovy.util.logging.Slf4j

@Slf4j
@SuppressWarnings('GroovyUnusedDeclaration')
class SearchMappingGlobalConfig {

    SearchMappingGlobalConfig() {
    }

    void analyzer(Map args, @DelegatesTo(SearchMappingGlobalConfig) Closure filters = null) {
        logUpdatedPropertyWarning('analyzer', 'https://docs.jboss.org/hibernate/search/6.0/migration/html_single/#analysis-definition-provider')
    }

    void normalizer(Map args, @DelegatesTo(SearchMappingGlobalConfig) Closure filters = null) {
        logUpdatedPropertyWarning('normalizer', 'https://docs.jboss.org/hibernate/search/6.0/migration/html_single/#analysis-definition-provider')
    }

    void filter(Class filterImpl) {
        logUpdatedPropertyWarning('filter', 'https://docs.jboss.org/hibernate/search/6.0/migration/html_single/#analysis-definition-provider')
    }

    void filter(Map filterParams) {
        logUpdatedPropertyWarning('filter', 'https://docs.jboss.org/hibernate/search/6.0/migration/html_single/#analysis-definition-provider')
    }

    void fullTextFilter(Map<String, Object> fullTextFilterParams) {
        logUpdatedPropertyWarning('fullTextFilter', 'https://docs.jboss.org/hibernate/search/6.0/migration/html_single/#full-text-filter')
    }

    private static void logUpdatedPropertyWarning(String method, String url) {
        log.warn('DEPRECATED: The method [{}] has been removed. See [{}]', method, url)
    }
}