package com.vibesales.salesagent.agent;

import io.agentscope.harness.agent.HarnessAgent;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 把本机 {@code agent-runtime} 作为 BYO Agent 注册到 AgentScope Service 控制面。
 *
 * <p>仅当 {@code AISTIO_CONTROL_PLANE_HTTP}（或 {@code AISTIO_CONTROL_HTTP}）与 {@code
 * BUILDER_INTERNAL_TOKEN} 同时存在，且 classpath 中存在 {@code agentscope-extensions-aistio}
 * 时生效，避免未起控制面或依赖缺失时干扰原有对话链路。
 */
final class AistioRuntimeBridge {

    private static final Logger LOG = Logger.getLogger(AistioRuntimeBridge.class.getName());

    private static final String AISTIO_CLASS = "io.agentscope.extensions.aistio.Aistio";
    private static final String AISTIO_CONFIG_CLASS = "io.agentscope.extensions.aistio.AistioConfig";

    private AistioRuntimeBridge() {}

    static void maybeAttach(HarnessAgent agent) {
        String controlHttp = firstNonBlank(env("AISTIO_CONTROL_PLANE_HTTP"), env("AISTIO_CONTROL_HTTP"));
        String token = env("BUILDER_INTERNAL_TOKEN");
        if (controlHttp.isBlank() || token.isBlank()) {
            return;
        }
        Class<?> aistioCls;
        Class<?> configCls;
        try {
            aistioCls = Class.forName(AISTIO_CLASS);
            configCls = Class.forName(AISTIO_CONFIG_CLASS);
        } catch (ClassNotFoundException ex) {
            LOG.log(Level.FINE,
                "agentscope-extensions-aistio not on classpath; skipping BYO agent registration",
                ex);
            return;
        }
        String agentName = firstNonBlank(env("AISTIO_AGENT_NAME"), SalesAgentFactory.DEFAULT_AGENT_NAME);
        int contractPort = parsePort(env("AISTIO_CONTRACT_HTTP_PORT"), 28191);
        String namespace = firstNonBlank(env("AISTIO_NAMESPACE"), "chatflows");
        String publicBaseUrl = env("AISTIO_PUBLIC_BASE_URL");
        try {
            Method builderMethod = configCls.getMethod("builder", String.class);
            Object builder = builderMethod.invoke(null, agentName);

            builder = chain(builder, "controlPlaneHttp", String.class, controlHttp);
            builder = chain(builder, "internalToken", String.class, token);
            builder = chain(builder, "namespace", String.class, namespace);
            builder = chain(builder, "contractHttpPort", int.class, contractPort);
            builder = chain(builder, "enableEvents", boolean.class, false);
            if (!publicBaseUrl.isBlank()) {
                builder = chain(builder, "publicBaseUrl", String.class, publicBaseUrl);
            }

            Method buildMethod = builder.getClass().getMethod("build");
            Object config = buildMethod.invoke(builder);

            Method instrumentMethod = aistioCls.getMethod("instrument", HarnessAgent.class, configCls);
            instrumentMethod.invoke(null, agent, config);

            LOG.info(() -> "registered agent-runtime with AgentScope Service at " + controlHttp);
        } catch (ReflectiveOperationException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            RuntimeException wrap = cause instanceof RuntimeException re ? re : new RuntimeException(cause);
            LOG.log(Level.WARNING, "failed to register agent-runtime with AgentScope Service", wrap);
        }
    }

    private static Object chain(Object builder, String method, Class<?> argType, Object arg)
            throws ReflectiveOperationException {
        Method m = findMethod(builder.getClass(), method, argType);
        if (m == null) {
            throw new NoSuchMethodException(builder.getClass().getName() + "." + method + "(" + argType.getName() + ")");
        }
        m.setAccessible(true);
        return m.invoke(builder, arg);
    }

    private static Method findMethod(Class<?> cls, String name, Class<?> argType) {
        Class<?> cursor = cls;
        while (cursor != null) {
            for (Method m : cursor.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0].isAssignableFrom(argType)) return m;
            }
            cursor = cursor.getSuperclass();
        }
        return null;
    }

    private static String env(String key) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        String property = System.getProperty(key);
        return property == null ? "" : property.trim();
    }

    private static String firstNonBlank(String left, String right) {
        return left.isBlank() ? right : left;
    }

    private static int parsePort(String raw, int fallback) {
        if (raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
