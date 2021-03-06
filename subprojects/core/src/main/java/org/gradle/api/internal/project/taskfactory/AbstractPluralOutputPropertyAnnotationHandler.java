/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.api.internal.project.taskfactory;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.internal.TaskInternal;
import org.gradle.internal.Cast;
import org.gradle.util.DeprecationLogger;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.gradle.internal.Cast.uncheckedCast;
import static org.gradle.util.GUtil.uncheckedCall;

public abstract class AbstractPluralOutputPropertyAnnotationHandler extends AbstractOutputPropertyAnnotationHandler {

    @Override
    public boolean attachActions(TaskPropertyActionContext context) {
        if (!Map.class.isAssignableFrom(context.getType())) {
            DeprecationLogger.nagUserOfDiscontinuedApi(
                String.format("use of the @%s annotation on non-Map properties",
                    getAnnotationType().getSimpleName()),
                getDeprecatedIterableMessage());
        }
        return super.attachActions(context);
    }

    abstract protected String getDeprecatedIterableMessage();

    @Override
    protected void validate(String propertyName, Object value, Collection<String> messages) {
        for (File file : toFiles(value)) {
            doValidate(propertyName, file, messages);
        }
    }

    protected abstract void doValidate(String propertyName, File file, Collection<String> messages);

    @Override
    protected void update(final TaskPropertyActionContext context, final TaskInternal task, final Callable<Object> futureValue) {
        if (Map.class.isAssignableFrom(context.getType())) {
            task.getOutputs().namedFiles(Cast.<Callable<Map<?, ?>>>uncheckedCast(futureValue));
        } else {
            DeprecationLogger.whileDisabled(new Runnable() {
                @Override
                @SuppressWarnings("deprecation")
                public void run() {
                    task.getOutputs().files(futureValue);
                }
            });
        }
        task.prependParallelSafeAction(new Action<Task>() {
            public void execute(Task task) {
                for (File file : toFiles(uncheckedCall(futureValue))) {
                    doEnsureExists(file);
                }
            }
        });
    }

    protected abstract void doEnsureExists(File file);

    private static Iterable<File> toFiles(Object value) {
        if (value == null) {
            return Collections.emptySet();
        } else if (value instanceof Map) {
            return uncheckedCast(((Map) value).values());
        } else {
            return uncheckedCast(value);
        }
    }
}
