package com.fondesa.quicksavestate.coder.base;

import com.fondesa.quicksavestate.coder.base.rule.CoderRule;

import org.junit.Rule;
import org.junit.Test;

import static com.fondesa.quicksavestate.coder.base.constants.Constants.COMMON_KEY;
import static junit.framework.Assert.assertEquals;

/**
 * Created by antoniolig on 22/02/17.
 */
public class IntCoderTest {
    @Rule
    public CoderRule<IntCoder> coderRule = new CoderRule<IntCoder>() {
        @Override
        protected IntCoder initCoder() {
            return new IntCoder();
        }
    };

    @Test
    public void testSerializeIntPrimitive() {
        int expectedValue = 9;
        coderRule.coder.serialize(coderRule.bundle, COMMON_KEY, expectedValue);
        assertEquals(expectedValue, coderRule.bundle.getInt(COMMON_KEY));
    }

    @Test
    public void testSerializeIntObject() {
        Integer expectedValue = 9;
        coderRule.coder.serialize(coderRule.bundle, COMMON_KEY, expectedValue);
        assertEquals(expectedValue, (Integer) coderRule.bundle.getInt(COMMON_KEY));
    }

    @Test
    public void testDeserializeIntPrimitive() {
        int expectedValue = 9;
        coderRule.bundle.putInt(COMMON_KEY, expectedValue);
        assertEquals((Integer) expectedValue, coderRule.coder.deserialize(coderRule.bundle, COMMON_KEY));
    }

    @Test
    public void testDeserializeIntObject() {
        Integer expectedValue = 9;
        coderRule.bundle.putInt(COMMON_KEY, expectedValue);
        assertEquals(expectedValue, coderRule.coder.deserialize(coderRule.bundle, COMMON_KEY));
    }
}