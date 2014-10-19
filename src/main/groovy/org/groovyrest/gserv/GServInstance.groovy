/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Lee Collins
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.groovyrest.gserv

import org.groovyrest.gserv.events.EventManager
import org.groovyrest.gserv.events.Events
import org.groovyrest.gserv.jmx.GServJMX
import org.groovyrest.gserv.jmx.GServJMXMXBean
import org.groovyrest.gserv.wrapper.ExchangeWrapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsServer
import groovy.jmx.builder.JmxBuilder
import groovy.util.logging.Log4j

import javax.management.Attribute
import javax.management.AttributeList
import javax.management.MBeanServer
import javax.management.MBeanServerConnection
import javax.management.ObjectInstance
import javax.management.ObjectName
import javax.management.StandardMBean
import javax.management.remote.JMXConnectorFactory
import javax.management.remote.JMXServiceURL
import javax.net.ssl.*
import java.lang.management.ManagementFactory
import java.rmi.registry.LocateRegistry
import java.security.KeyStore

/**
 * An HTTP Server Instance.  Pass a port number to the start() method to start the instance on that port.
 */
@Log4j
class GServInstance {
    protected def _authenticator
    protected com.sun.net.httpserver.HttpHandler _handler
    protected def _executor
    protected def _filters
    protected def _templateEngineName;
    protected def _cfg;

    /**
     *
     * @param cfg
     * @return
     */
    def GServInstance(cfg) {
        _cfg = cfg
        _handler = new gServHandler(cfg);
        _authenticator = cfg.authenticator();
        _filters = cfg.filters();
        _templateEngineName = cfg.templateEngineName();
    }

    def mbean

    def exportMBean(actualPort, jmxBean) {

        System.properties.put('com.sun.management.jmxremote.ssl', true);
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName("com.gserv.$actualPort:type=GServJMX");
        JmxBuilder jmx = new JmxBuilder(mbs)
        def connection = jmx.server(port: 8090)

//        def beans = jmx.export {
//            bean(jmxBean){
//                attributes: "*"
//            }
//        }
        def beans = jmx.export(policy: "replace") {
            bean(
                    target: jmxBean,
                    name: name
            )
        }

        this.mbean = (GroovyMBean) beans[0]
//        println "JMX MBean: ${this.mbean}"
        this.mbean
    }

    /**
     * This method will start the server on 'port'.
     *
     * @param port
     */
    def start(port = null) {
        def actualPort = (port ?: _cfg.port())

        exportMBean(actualPort,
                new GServJMX()
        );

        def server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(actualPort as Integer), 0);
        def context = server.createContext("/", _handler);
        def requestId = 1L;

        ////////////////////////////////
        /// create and add the InitFilter
        ////////////////////////////////
        def initFilter = RouteFactory.createBeforeFilterURLPattern("gServInit", "*", "/*", [:], -1) { ->
            synchronized (requestId) {
                log.debug("initFilter: new request #$requestId")
                exchange.setAttribute(GServ.exchangeAttributes.requestId, requestId)
                ++requestId
            }
            /// here we should check for a blank file name
            /// if file name is blank and we have a defaultResource then we use that.
            exchange = applyDefaultResourceIfNecessary(exchange)
            nextFilter()
            exchange
        }

        _filters = _filters ?: []
        _filters.add(initFilter);

        // if we have filters then create the proxies
        if (_filters) {
            _filters.sort({ a, b -> a.order - b.order }).each {
                // each FilterProxy represents ONE GServ Filter -- this may change
                context.filters.add(new org.groovyrest.gserv.filters.FilterProxy([it], _cfg))
            }
        }
        context.authenticator = _authenticator;
        server.executor = _executor;
        def appName = _cfg.name() ?: "gserv"
        println "$appName starting HTTP on port ${server.address.port}"
        EventManager.instance().publish(Events.ServerStarted, [port: actualPort])
        Thread.start {
            server.start();
        }
        return ({ ->
            server.stop(0);
        });
    };

    def config() {
        return _cfg;
    }

    /// here we should check for a blank file name
    /// if file name is blank and we have a defaultResource then we use that.
    HttpExchange applyDefaultResourceIfNecessary(com.sun.net.httpserver.HttpExchange exchange) {
        if (!config().defaultResource())
            return exchange;
        def path = exchange.requestURI.path
        def bUseDefaultResource = (!path || path == '\\')
        if (!bUseDefaultResource)
            return exchange;
        ExchangeWrapper nuExchange = new ExchangeWrapper(exchange);
        URI oldUri = nuExchange.requestURI
        URI uri = new URI(
                oldUri.scheme, oldUri.userInfo, oldUri.host,
                oldUri.port, config().defaultResource(), oldUri.query, oldUri.fragment);
        nuExchange.requestURI = uri
        //new URI(scheme userInfo, host,port,path, query, fragment)
        nuExchange
    }

}

@Log4j
class gServHttpsInstance extends GServInstance {

    gServHttpsInstance(cfg) {
        super(cfg)
        if (!cfg.https())
            throw new IllegalArgumentException("HTTPS must be configured for this Instance.")
    }

    /**
     * This method will start the server on 'port'.
     *
     * @param port
     */
    def start(port = null) {
        def actualPort = (port ?: _cfg.port())

        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName("com.gserv.$actualPort:type=GServJMX");
        GServJMX mbean = new GServJMX();
        mbs.registerMBean(mbean, name);

        def httpsConfig = this._cfg.httpsConfig()
        assert (httpsConfig.password)
        def keyManagerAlgorithm = httpsConfig.keyManagerAlgorithm ?: "SunX509"
        def trustManagerAlgorithm = httpsConfig.trustManagerAlgorithm ?: "SunX509"
        def keyStoreFilePath = httpsConfig.keyStoreFilePath ?: (System.getProperty("user.home") + "/gserv.keystore")
        def keyStoreImplementation = httpsConfig.keyStoreImplementation ?: "JKS"
        def sslProtocol = httpsConfig.sslProtocol ?: "TLS"
        def httpsPassword = httpsConfig.password
        def server = HttpsServer.create(new InetSocketAddress(actualPort as Integer), 0);
        SSLContext sslContext = SSLContext.getInstance(sslProtocol);

        // initialise the keystore
        char[] password = httpsPassword.toCharArray();
        KeyStore ks = KeyStore.getInstance(keyStoreImplementation);
        FileInputStream fis = new FileInputStream(keyStoreFilePath);
        ks.load(fis, password);

        // setup the key manager factory
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(keyManagerAlgorithm);
        kmf.init(ks, password);

        // setup the trust manager factory
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(trustManagerAlgorithm);
        tmf.init(ks);

        // setup the HTTPS context and parameters
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        server.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(sslContext) {

            @Override
            void configure(com.sun.net.httpserver.HttpsParameters params) {
                log.debug("HTTPSConfigurator.onConfigure($params) - handling");
                try {
                    // initialise the SSL context
                    SSLContext c = sslContext;//.getDefault();
                    SSLEngine engine = c.createSSLEngine();
                    params.setNeedClientAuth(false);
                    params.setCipherSuites(engine.getEnabledCipherSuites());
                    params.setProtocols(engine.getEnabledProtocols());

                    // get the default parameters
                    SSLParameters defaultSSLParameters = c.getDefaultSSLParameters();
                    params.setSSLParameters(defaultSSLParameters);
                    super.configure(params)
                }
                catch (Exception ex) {
                    log.error("Failed to create HTTPS port: ", ex);
                } finally {
                    log.debug("HTTPSConfigurator.onConfigure($params) - handled!");
                }
            }
        });
        def context = server.createContext("/", _handler);
        def requestId = 1L;

        ////////////////////////////////
        /// create and add the InitFilter
        ////////////////////////////////
        def initFilter = RouteFactory.createBeforeFilterURLPattern("gServInit", "*", "/*", [:], -1) { ->
            synchronized (requestId) {
                exchange.setAttribute(GServ.exchangeAttributes.requestId, requestId)
                ++requestId
            }
            nextFilter()
            exchange
        }

        _filters = _filters ?: []
        _filters.add(initFilter);

        // if we have filters then create the proxies
        if (_filters) {
            _filters.sort({ a, b -> a.order - b.order }).each {
                // each FilterProxy represents ONE GServ Filter -- this may change
                context.filters.add(new org.groovyrest.gserv.filters.FilterProxy([it], _cfg))
            }
        }
        context.authenticator = _authenticator;
        server.executor = _executor;
        def appName = _cfg.name() ?: "gserv"
        println "$appName starting HTTPS on port ${server.address.port}"

        EventManager.instance().publish(Events.ServerStarted, [port: actualPort])
        Thread.start {
            server.start();
        }
        return ({ ->
            server.stop(0);
        });
    }

}