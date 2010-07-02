package org.grails.plugins.oauth

/*
 * Copyright 2008 the original author or authors.
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

import org.springframework.beans.factory.InitializingBean
import org.codehaus.groovy.grails.commons.ConfigurationHolder as C

import oauth.signpost.OAuth
import oauth.signpost.OAuthConsumer
import oauth.signpost.OAuthProvider
import oauth.signpost.basic.DefaultOAuthConsumer
import oauth.signpost.basic.DefaultOAuthProvider

class OauthService implements InitializingBean {
    // Transactional service
    boolean transactional = false

    // Set scope to be session
    static scope = "session"

    // Service properties
    def providers = [:]
    def consumers = [:]
    def authUrls = [:]
    String callback = ""
    
    /**
     * Parses OAuth settings in Config.groovy and propagates providers and consumers
     * 
     * Example OAuth settings format in Config.groovy
     * 
     * e.g. Single consumer per provider
     * 
     * oauth {
     * 		provider_name {
     * 			requestTokenUrl='http://example.com/oauth/request_token'
     *			accessTokenUrl='http://example.com/oauth/access_token'
     *			authUrl='http://example.com/oauth/authorize'
     *			scope = 'http://example.com/data/feed/api/'
     *			consumer.key = 'key'
     *			consumer.secret = 'secret'
     *		}
     * }
     * 
     * e.g. Multiple consumers per provider
     * 
     * oauth {
     * 		provider_name {
     * 			requestTokenUrl = 'http://example.com/oauth/request_token'
     *			accessTokenUrl = 'http://example.com/oauth/access_token'
     *			authUrl = 'http://example.com/oauth/authorize'
     *			scope = 'http://example.com/data/feed/api/'
     *			consumers {
     *				consumer_name {
     *					key = 'key'
     *					secret = 'secret'
     *				}
     *				consumer_name_a {
     *					key = 'key'
     *					secret = 'secret'
     *				}
     *			}
     *		}
     * }
     *
     * Note: The scope provider specific property and is a optional. Only providers
     * such as Google's GDATA API make use of this property.
     */
    void afterPropertiesSet() {
        println "Initializating OauthService"
        
        // Initialize consumer list by reading config
        final String serverURL = C.config.grails.serverURL.toString()
        if (!serverURL.endsWith('/')) {
        	serverURL += '/'
        }

        // Create call back link
        callback = serverURL + "oauth/callback"
        println "- Callback URL: ${callback}"
        
        C.config.oauth.each { key, value ->
            println "Provider: ${key}"
            println "- Signed: ${value?.signed}"

            def requestTokenUrl = value?.requestTokenUrl
            if (value?.scope) {
                println "- Scope: " + value?.scope

                requestTokenUrl = requestTokenUrl + "?scope=" + URLEncoder.encode(value?.scope, "utf-8")
            }

            println "- Request token URL: ${requestTokenUrl}"
            println "- Access token URL: ${value?.accessTokenUrl}"
            println "- Authorisation URL: ${value?.authUrl}\n"

            // Initialise provider
            providers[key] = new DefaultOAuthProvider(requestTokenUrl,
                value?.accessTokenUrl, value?.authUrl)
	        
	        if (value?.consumer) {
	        	/*
                 * Default single consumer if single consumer defined, will not go on to parse
                 * multiple consumers.
                 */
	        	println "- Single consumer:"
	        	println "--- Key: ${value?.consumer?.key}"
	        	println "--- Secret: ${value?.consumer?.secret}"

                consumers[key] = new DefaultOAuthConsumer(value.consumer.key,
                    value.consumer.secret)

	        } else if (value?.consumers) {
	        	// Multiple consumers from same provider
	        	println "- Multiple consumers:"

	        	final def allConsumers = value?.consumers
	        	allConsumers.each { name, token ->
	        		println "--- Consumer: ${name}"
                    println "----- Key: ${token?.key}"
                    println "----- Secret: ${token?.secret}"

                    consumers[name] = new DefaultOAuthConsumer(token?.key, token?.secret)
	        	}
	        } else {
	        	println "Error initializaing OauthService: No consumers defined!"
	        }   
        }
    }

    /**
     * Retrieves an unauthorized request token from the OAuth service.
     *
     * @param consumerName the consumer to fetch request token from.
     * @return A map containing the token key, secret and authorisation URL.
     */
    def fetchRequestToken(consumerName) {
        log.debug "Fetching request token for ${consumerName}"

        try {
            // Get consumer and provider
            final DefaultOAuthConsumer consumer = getConsumer(consumerName)
            final DefaultOAuthProvider provider = getProvider(consumerName)

            // Retrieve request token
            authUrls[consumerName]  = provider?.retrieveRequestToken(consumer, callback)

            log.debug "Request token: ${consumer?.getToken()}"
            log.debug "Token secret: ${consumer?.getTokenSecret()}\n"

            [key: consumer?.getToken(), secret: consumer?.getTokenSecret()]

        } catch (Exception ex) {
            final def errorMessage = "Unable to fetch request token: consumerName = $consumerName"

            log.error(errorMessage, ex)
            throw new OauthServiceException(errorMessage, ex)
        }
    }

    /**
     * Constructs the URL for user authorization action, with required parameters appended.
     *
     * @deprecated as of 0.2. Replaced with {@link #getAuthUrl(java.lang.String)}
     * @param key the token key.
     * @param consumerName the consumer name.
     * @params params any URL params.
     * @return The URL to redirect the user to for authorisation.
     */
    @Deprecated
    def getAuthUrl(key, consumerName, params) {
        log.debug "Fetching authorisation URL for $consumerName"

        authUrls[consumerName]
    }

    /**
     * Exchanges the authorized request token for the access token.
     *
     * @return A map containing the access token and secret.
     */
    def fetchAccessToken(consumerName, requestToken) {
        log.debug "Going to exchange for access token"

        try {
            final DefaultOAuthConsumer consumer = getConsumer(consumerName)
            final DefaultOAuthProvider provider = getProvider(consumerName)

            // Retrieve access token
            provider.retrieveAccessToken(consumer, requestToken.verifier)

            final def accessToken = consumer?.getToken()
            final def tokenSecret = consumer?.getTokenSecret()

            log.debug "Access token: $accessToken"
            log.debug "Token secret: $tokenSecret"

            if (!accessToken || !tokenSecret) {
                final def errorMessage = "Unable to fetch access token, access token is missing! " +
                    "consumerName = $consumerName, requestToken = $requestToken, " +
                    "accessToken = $accessToken, tokenSecret = $tokenSecret"

                log.error(errorMessage, ex)
                throw new OauthServiceException(errorMessage, ex)
            }

            [key: accessToken, secret: tokenSecret]

        } catch (Exception ex) {
            final def errorMessage = "Unable to fetch access token: consumerName = $consumerName, " +
                "requestToken = $requestToken"

            log.error(errorMessage, ex)
            throw new OauthServiceException(errorMessage, ex)
        }
    }
    
    /**
     * Helper function with default parameters to access an OAuth protected resource.
     *
     * @param url URL to the protected resource.
     * @param consumer the consumer.
     * @param token the access token.
     * @param method HTTP method, whether to use POST or GET.
     * @param params any request parameters.
     * @return the response from the server.
     */
    def accessResource(url, consumer, token, method = 'GET', params = null) {
    	accessResource(url: url, consumer: consumer, token: token, method: method, params: params)
    }
    
    /**
     * Helper function with named parameters to access an OAuth protected resource.
     *
     * @param args access resource arguments.
     * @return the response from the server.
     */
    def accessResource(Map args) {
        log.debug "Attempt to access protected resource"

        // Declare request parameters
        def method
        def params
        URL url
        DefaultOAuthConsumer consumer

        try {
            method = args?.get('method','GET')
            params = args?.params
            url = new URL(args?.url)
            consumer = getConsumer(args?.consumer)

            if (!consumer) {
                final def errorMessage = "Unable to access to procected resource, invalid consumer: " +
                    "method = $method, params = $params, url = $url, consumer = $consumer"

                log.error(errorMessage, ex)
                throw new OauthServiceException(errorMessage, ex)
            }

            def token = args?.token
            if (!token || !token?.key || !token?.secret) {
                final def errorMessage = "Unable to access to procected resource, invalid token: " +
                    "method = $method, params = $params, url = $url, consumer = $consumer, " +
                    "token = $token, token.key = ${token?.key}, token.secret = ${token?.secret}"

                log.error(errorMessage, ex)
                throw new OauthServiceException(errorMessage, ex)
            }

            log.debug "Open connection to $url"

            // Create an HTTP request to a protected resource
            HttpURLConnection request = (HttpURLConnection) url.openConnection()
            
            if (params) {
                log.debug "Putting additional params: $params"

                params.each { key, value ->
                    request.addRequestProperty(key, value)
                }

                log.debug "Request properties are now: ${request?.getRequestProperties()}"
            }
            
            // Sign the request
            consumer.sign(request)

            log.debug "Send request..."

            // Send the request
            request.connect()

            log.debug "Return response..."

            // Return the request response
            request.getResponseMessage()

        } catch (Exception ex) {
            final def errorMessage = "Unable to access to procected resource: method = $method, " +
                "params = $params, url = $url, consumer = $consumer"
        
            log.error(errorMessage, ex)
            throw new OauthServiceException(errorMessage, ex)
        }
    }

    /**
     * Returns the current consumer for the provided name.
     *
     * @param consumerName the consumer name.
     * @return the consumer instance by name.
     */
    def getConsumer(consumerName) {
    	consumers[consumerName]
    }

    /**
     * Returns the current provider for the provided consumer.
     *
     * @param consumerName the consumer name.
     * @return the provider instance by name.
     */
    def getProvider(consumerName) {
    	providers[consumerName]
    }

    /**
     * Returns the current authorisation URL for the provided consumer.
     *
     * @param consumerName the consumer name.
     * @return the authorisational URL instance by consumer name.
     */
    def getAuthUrl(consumerName) {
        log.debug "Fetching authorisation URL for $consumerName"

    	authUrls[consumerName]
    }
}