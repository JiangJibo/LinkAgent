/*
 * Copyright 2021 Shulie Technology, Co.Ltd
 * Email: shulie@shulie.io
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pamirs.attach.plugin.cluster.test.check;

import com.pamirs.attach.plugin.cluster.test.check.interceptor.web.*;
import com.shulie.instrument.simulator.api.ExtensionModule;
import com.shulie.instrument.simulator.api.ModuleInfo;
import com.shulie.instrument.simulator.api.ModuleLifecycleAdapter;
import com.shulie.instrument.simulator.api.instrument.EnhanceCallback;
import com.shulie.instrument.simulator.api.instrument.InstrumentClass;
import com.shulie.instrument.simulator.api.instrument.InstrumentMethod;
import com.shulie.instrument.simulator.api.listener.Listeners;
import org.kohsuke.MetaInfServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @Description
 * @Author ocean_wll
 * @Date 2022/8/22 14:16
 */
@MetaInfServices(ExtensionModule.class)
@ModuleInfo(id = "cluster-test-check", version = "1.0.0", author = "ocean_wll", description = "应用启动校验，压测是否就绪")
public class CheckPlugin extends ModuleLifecycleAdapter implements ExtensionModule {
    private static final Logger logger = LoggerFactory.getLogger(CheckPlugin.class);

    @Override
    public boolean onActive() throws Throwable {
        logger.info("start enhance cluster-test-check module");

        // 处理web类型
        enhanceWeb();

        return true;
    }

    private void enhanceWeb() {
        this.enhanceTemplate.enhanceWithInterface(this, "javax.servlet.Servlet", new EnhanceCallback() {
            @Override
            public void doEnhance(InstrumentClass target) {
                InstrumentMethod method = target.getDeclaredMethod("service", "javax.servlet.ServletRequest",
                        "javax.servlet.ServletResponse");
                method.addInterceptor(Listeners.of(ServletInterceptor.class));
            }
        });

        this.enhanceTemplate.enhanceWithInterface(this, "io.undertow.server.HttpHandler", new EnhanceCallback() {
            @Override
            public void doEnhance(InstrumentClass target) {
                InstrumentMethod method = target.getDeclaredMethod("handleRequest", "io.undertow.server.HttpServerExchange");
                method.addInterceptor(Listeners.of(UndertowHttpHandlerInterceptor.class));
            }
        });

        this.enhanceTemplate.enhanceWithInterface(this, "org.springframework.http.server.reactive.HttpHandler", new EnhanceCallback() {
            @Override
            public void doEnhance(InstrumentClass target) {
                InstrumentMethod method = target.getDeclaredMethod("handle", "org.springframework.http.server.reactive.ServerHttpRequest",
                        "org.springframework.http.server.reactive.ServerHttpResponse");
                method.addInterceptor(Listeners.of(SpringHttpHandlerInterceptor.class));
            }
        });

        this.enhanceTemplate.enhanceWithInterface(this, "org.springframework.web.server.WebHandler", new EnhanceCallback() {
            @Override
            public void doEnhance(InstrumentClass target) {
                InstrumentMethod method = target.getDeclaredMethod("handle", "org.springframework.web.server.ServerWebExchange");
                method.addInterceptor(Listeners.of(SpringWebHandlerInterceptor.class));
            }
        });

        this.enhanceTemplate.enhanceWithInterface(this, "io.netty.channel.ChannelInboundHandler", new EnhanceCallback() {
            @Override
            public void doEnhance(InstrumentClass target) {
                InstrumentMethod method = target.getDeclaredMethod("channelRead", "io.netty.channel.ChannelHandlerContext", "java.lang.Object");
                method.addInterceptor(Listeners.of(ChannelInboundHandlerInterceptor.class));
            }
        });
    }
}
