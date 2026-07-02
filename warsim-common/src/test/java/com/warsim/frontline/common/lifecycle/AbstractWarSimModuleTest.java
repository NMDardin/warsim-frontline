package com.warsim.frontline.common.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.warsim.frontline.api.ModuleState;
import org.junit.jupiter.api.Test;

class AbstractWarSimModuleTest {
    @Test
    void lifecycleIsIdempotent() throws Exception {
        CountingModule module = new CountingModule();
        module.start();
        module.start();
        assertEquals(ModuleState.RUNNING, module.state());
        assertEquals(1, module.starts);

        module.stop();
        module.stop();
        assertEquals(ModuleState.STOPPED, module.state());
        assertEquals(1, module.stops);
    }

    private static final class CountingModule extends AbstractWarSimModule {
        private int starts;
        private int stops;

        private CountingModule() {
            super("test");
        }

        @Override
        protected void onStart() {
            starts++;
        }

        @Override
        protected void onStop() {
            stops++;
        }
    }
}
