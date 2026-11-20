package com.bareminimumstudios.guardian.platform.minecraft;

import org.junit.jupiter.api.extension.*;

/** Keep service discovery beside transformed tests and KFF's shared Kotlin runtime. */
public final class GameLayerTestContext implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {
    private static final ExtensionContext.Namespace KEY=ExtensionContext.Namespace.create(GameLayerTestContext.class);
    private void enter(ExtensionContext context,String scope) {
        context.getStore(KEY).put(scope,Thread.currentThread().getContextClassLoader());
        Thread.currentThread().setContextClassLoader(context.getRequiredTestClass().getClassLoader());
    }
    private void leave(ExtensionContext context,String scope) {
        Thread.currentThread().setContextClassLoader(context.getStore(KEY).remove(scope,ClassLoader.class));
    }
    @Override public void beforeAll(ExtensionContext context) { enter(context,"class"); }
    @Override public void afterAll(ExtensionContext context) { leave(context,"class"); }
    @Override public void beforeEach(ExtensionContext context) { enter(context,"test"); }
    @Override public void afterEach(ExtensionContext context) { leave(context,"test"); }
}
