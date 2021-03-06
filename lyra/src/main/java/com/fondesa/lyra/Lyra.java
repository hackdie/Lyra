/*
 * Copyright (c) 2017 Fondesa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.fondesa.lyra;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.util.Log;

import com.fondesa.lyra.annotation.SaveState;
import com.fondesa.lyra.coder.CoderRetriever;
import com.fondesa.lyra.coder.DefaultCoderRetriever;
import com.fondesa.lyra.coder.StateCoder;
import com.fondesa.lyra.exception.CoderNotFoundException;
import com.fondesa.lyra.field.DefaultFieldsRetriever;
import com.fondesa.lyra.field.FieldsRetriever;

import java.lang.reflect.Field;

/**
 * Manager to save/restore the fields' state of an {@link Object}'s instance into/from a {@link Bundle}.
 * <br>
 * You have to annotate the global variables that you want to save/restore with the runtime annotation {@link SaveState}.
 * All basic types will be resolved through basic coders contained in the package {@link com.fondesa.lyra.coder.base}.
 * If you want to use a custom coder, you have to implement {@link StateCoder} interface and manage manually the
 * write to a {@link Bundle} and the read from a {@link Bundle}. After, you have to annotate your field with the
 * {@link SaveState} annotation passing the new coder as the value inside the annotation.
 * <br>
 * The save/restore process supports class' inheritance. You can annotate a field of a class and all its subclasses (if
 * they use {@link Lyra}) will save and restore the state of that field.
 * <br>
 * This class uses the singleton pattern so you must initialize it once using the provided {@link Builder}.
 * After, you can access to instance methods with {@link Lyra#instance()}.
 */
public class Lyra {
    public static final String SUB_BUNDLE_KEY = "lyra:";
    private static final String TAG = Lyra.class.getSimpleName();

    private Application mApplication;
    private CoderRetriever mCoderRetriever;
    private FieldsRetriever mFieldsRetriever;
    private AutomaticSaveStateManager mAutomaticSaveStateManager;

    @SuppressLint("StaticFieldLeak")
    private static Lyra instance;

    /**
     * Creates a {@link Builder} instance to specify {@link Lyra}'s configurations.
     *
     * @param application {@link Application}'s instance used to create the {@link Builder}
     * @return a new {@link Builder} to generate the singleton instance of {@link Lyra}
     */
    public static Builder with(@NonNull Application application) {
        return new Lyra.Builder().with(application);
    }

    /**
     * Destroy the singleton instance if it's initialized.
     * This will avoid memory leak due to usage of {@link Application} inside a singleton class.
     * If there's an {@link AutomaticSaveStateManager} registered for lifecycle callbacks,
     * it will be unregistered.
     */
    @SuppressLint("NewApi")
    public static void destroy() {
        if (isInitialized()) {
            if (instance.mAutomaticSaveStateManager != null) {
                instance.mApplication.unregisterActivityLifecycleCallbacks(instance.mAutomaticSaveStateManager);
            }
            // Avoid memory leak bringing Application instance to null.
            instance.mApplication = null;
            instance = null;
        }
    }

    /**
     * Check if the singleton instance of {@link Lyra} is initialized.
     *
     * @return true if the instance is not null
     */
    public static boolean isInitialized() {
        return instance != null;
    }

    /**
     * Get the singleton instance of {@link Lyra} if it's initialized.
     *
     * @return not null instance of {@link Lyra}
     */
    @NonNull
    public static Lyra instance() {
        if (!isInitialized()) {
            throw new NullPointerException("Instance not initialized. You have to build it in your application.");
        }
        return instance;
    }

    /**
     * Get the key used to save/restore a class' field.
     * The format will be: {@code fieldDeclaringClass#fieldName}.
     *
     * @param field {@link Field} used to get the key
     * @return key used to save/restore a {@link Field} instance
     */
    @NonNull
    public static String getKeyFromField(@NonNull Field field) {
        return field.getDeclaringClass().getName() + '#' + field.getName();
    }

    /**
     * Save the annotated fields' state of a class into a {@link Bundle}.
     * The fields of a class will be retrieved through a {@link FieldsRetriever}.
     * The coder to serialize the fields into a {@link Bundle} will be retrieved through a {@link CoderRetriever}.
     *
     * @param stateHolder instance of the class with annotated fields
     * @param state       {@link Bundle} in which you want to save the annotated fields
     */
    public void saveState(@NonNull Object stateHolder, @NonNull Bundle state) {
        // Get the instance through the method to have the correct exception handling if instance is null.
        final Lyra lyra = instance();
        Field[] fields = lyra.mFieldsRetriever.getFields(stateHolder.getClass());

        Bundle lyraBundle = new Bundle();

        //noinspection ForLoopReplaceableByForEach
        for (int i = 0; i < fields.length; i++) {
            Field field = fields[i];
            SaveState saveState = getSaveStateAnnotation(field);
            // If the field isn't accessible, it will be modified in accessible and will return inaccessible after.
            boolean accessible = field.isAccessible();
            if (!accessible) {
                field.setAccessible(true);
            }

            final Object fieldValue;
            try {
                // Get the field value.
                fieldValue = field.get(stateHolder);
            } catch (IllegalAccessException e) {
                throw runtimeIllegalAccessException(field);
            }

            if (fieldValue != null) {
                final StateCoder stateCoder;
                try {
                    stateCoder = lyra.mCoderRetriever.getCoder(saveState, field.getType());
                } catch (CoderNotFoundException e) {
                    throw new RuntimeException(e);
                }
                //noinspection unchecked
                stateCoder.serialize(lyraBundle, getKeyFromField(field), fieldValue);
            }

            if (!accessible) {
                // Modify the field to make it inaccessible again.
                field.setAccessible(false);
            }
        }

        // Put the saved fields in the sub Bundle of Lyra.
        state.putBundle(SUB_BUNDLE_KEY, lyraBundle);
    }

    /**
     * Restore the annotated fields' state of a class from a {@link Bundle}.
     * The fields of a class will be retrieved through a {@link FieldsRetriever}.
     * The coder to deserialize the fields from a {@link Bundle} will be retrieved through a {@link CoderRetriever}.
     *
     * @param stateHolder instance of the class with annotated fields
     * @param state       {@link Bundle} from which you want to restore the value of the annotated fields
     */
    public void restoreState(@NonNull Object stateHolder, @Nullable Bundle state) {
        Bundle lyraBundle;
        // Restore the saved fields from the sub Bundle of Lyra.
        if (state == null || (lyraBundle = state.getBundle(SUB_BUNDLE_KEY)) == null)
            return;

        // Get the instance through the method to have the correct exception handling if instance is null.
        final Lyra lyra = instance();
        Field[] fields = lyra.mFieldsRetriever.getFields(stateHolder.getClass());
        //noinspection ForLoopReplaceableByForEach
        for (int i = 0; i < fields.length; i++) {
            Field field = fields[i];
            SaveState saveState = getSaveStateAnnotation(field);
            // If the field isn't accessible, it will be modified in accessible and will return inaccessible after.
            boolean accessible = field.isAccessible();
            if (!accessible) {
                field.setAccessible(true);
            }

            final StateCoder stateCoder;
            try {
                stateCoder = lyra.mCoderRetriever.getCoder(saveState, field.getType());
            } catch (CoderNotFoundException e) {
                throw new RuntimeException(e);
            }
            final Object fieldValue = stateCoder.deserialize(lyraBundle, getKeyFromField(field));
            if (fieldValue != null) {
                try {
                    // Set the field value.
                    field.set(stateHolder, fieldValue);
                } catch (IllegalAccessException e) {
                    throw runtimeIllegalAccessException(field);
                }
            }

            if (!accessible) {
                // Modify the field to make it inaccessible again.
                field.setAccessible(false);
            }
        }
    }

    @NonNull
    private static SaveState getSaveStateAnnotation(@NonNull Field field) {
        SaveState saveState = field.getAnnotation(SaveState.class);
        if (saveState == null) {
            throw new IllegalArgumentException("You must provide a list of fields with the "
                    + SaveState.class.getName() + " annotation");
        }
        return saveState;
    }

    private static RuntimeException runtimeIllegalAccessException(@NonNull Field field) {
        return new RuntimeException("Cannot access to field " + field.getName() +
                " of class " + field.getDeclaringClass().getName());
    }

    @SuppressLint("NewApi")
    private Lyra(@NonNull Application application,
                 @NonNull CoderRetriever coderRetriever,
                 @NonNull FieldsRetriever fieldsRetriever,
                 boolean autoSaveActivities) {

        mApplication = application;
        mCoderRetriever = coderRetriever;
        mFieldsRetriever = fieldsRetriever;

        if (autoSaveActivities) {
            // The registration for lifecycle events is available above api 14.
            final int neededApi = Build.VERSION_CODES.ICE_CREAM_SANDWICH;
            if (Build.VERSION.SDK_INT >= neededApi) {
                // Set the listener to handle the save and the restore of the state.
                AutomaticSaveStateManager.Listener listener = new AutomaticSaveStateManager.Listener() {
                    @Override
                    public void onSaveState(@NonNull Activity activity, @NonNull Bundle outState) {
                        Lyra.instance().saveState(activity, outState);
                    }

                    @Override
                    public void onRestoreState(@NonNull Activity activity, @Nullable Bundle savedState) {
                        Lyra.instance().restoreState(activity, savedState);
                    }
                };
                mAutomaticSaveStateManager = new AutomaticSaveStateManager(listener);
                // Register the AutomaticSaveStateManager for activities' lifecycle events.
                mApplication.registerActivityLifecycleCallbacks(mAutomaticSaveStateManager);
            } else {
                Log.e(TAG, "State can't be automatically saved below api " + neededApi);
            }
        }
    }

    /**
     * Builder class used to create the singleton instance of {@link Lyra}.
     * <br>
     * To create a new instance of this {@link Builder} use {@link Lyra#with(Application)}.
     */
    public static final class Builder {
        private Application mApplication;
        private CoderRetriever mCoderRetriever;
        private FieldsRetriever mFieldsRetriever;
        private boolean mAutoSaveActivities;

        Builder() {
            // Package protected constructor to avoid the instantiation outside this package.
        }

        /**
         * Set the {@link Application} that must be used as {@link Context} for this {@link Builder}.
         *
         * @param application instance of {@link Application} in which the {@link Builder} is created
         * @return instance of the previously created {@link Builder}
         */
        Builder with(@NonNull Application application) {
            mApplication = application;
            return this;
        }

        /**
         * Set the {@link CoderRetriever} that must be used to retrieve the coder related to a java class.
         * <br>
         * DEFAULT: {@link DefaultCoderRetriever}
         *
         * @param coderRetriever instance of {@link CoderRetriever} used to retrieve a {@link StateCoder}
         * @return instance of the previously created {@link Builder}
         */
        public Builder coderRetriever(@Nullable CoderRetriever coderRetriever) {
            mCoderRetriever = coderRetriever;
            return this;
        }

        /**
         * Set the {@link FieldsRetriever} that must be used to retrieve the fields declared in a java class.
         * <br>
         * DEFAULT: {@link DefaultFieldsRetriever}
         *
         * @param fieldsRetriever instance of {@link FieldsRetriever} used to retrieve the list of fields
         * @return instance of the previously created {@link Builder}
         */
        public Builder fieldsRetriever(@Nullable FieldsRetriever fieldsRetriever) {
            mFieldsRetriever = fieldsRetriever;
            return this;
        }

        /**
         * Allows the automatic save/restore of the state in every {@link Activity}.
         * It can be done through {@link Application#registerActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks)}.
         * An instance of {@link AutomaticSaveStateManager} will be attached to the {@link Lyra} to manage
         * the automatic save/restore of the state.
         * <br>
         * DEFAULT: false (the save/restore of the state must be called manually)
         *
         * @return instance of the previously created {@link Builder}
         */
        @RequiresApi(api = Build.VERSION_CODES.ICE_CREAM_SANDWICH)
        public Builder autoSaveActivities() {
            mAutoSaveActivities = true;
            return this;
        }

        /**
         * Creates an instance of {@link Lyra} with the previously specified {@link Builder}'s
         * configurations or defaults if those are not defined.
         */
        public void build() {
            if (mCoderRetriever == null) {
                mCoderRetriever = new DefaultCoderRetriever();
            }

            if (mFieldsRetriever == null) {
                mFieldsRetriever = new DefaultFieldsRetriever();
            }

            if (instance != null) {
                Log.w(TAG, "The instance is initialized. You are building it multiple times.");
            }

            // Create the singleton instance.
            instance = new Lyra(mApplication, mCoderRetriever, mFieldsRetriever, mAutoSaveActivities);
        }
    }
}