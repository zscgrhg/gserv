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

package com.soulsys.gserv.configuration.scriptloader

import com.soulsys.gserv.GServInstance
import com.soulsys.gserv.configuration.GServConfig
import com.soulsys.gserv.resourceloader.ResourceLoader

/**
 * Created by lcollins on 8/26/2014.
 */
class ScriptLoader {

    ResourceLoader resourceLoader = new ResourceLoader();

    GServConfig loadInstance(instanceScript, classLoader) {
        def f = new File(instanceScript)
        if (!f.exists()) {
            System.err.println("No gserv instanceScript: ${f.absolutePath}")
            return null
        }
        /// script file exists - so run  it
        return (resourceLoader.loadInstance(f, classLoader) ?: null)
    }

    List<GServInstance> loadResources(resourceScripts, classLoader) {
        (!resourceScripts) ? [] :
                resourceScripts.collect { scriptFileName ->
                    def f = new File(scriptFileName)
                    if (!f.exists()) {
                        System.err.println("No resourceScript: ${f.absolutePath}")
                        return []
                    }
                    return (resourceLoader.loadResources(f, classLoader) ?: [])
                }.flatten()
    };////
//
//    GServConfig addResources(resourceScripts, config) {
//        def resources = loadResources(resourceScripts)
//        config.addResources(resources)
//        config;
//    };////

}
