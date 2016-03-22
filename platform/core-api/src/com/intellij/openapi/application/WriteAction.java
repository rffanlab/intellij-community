/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.application;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.util.ObjectUtils;
import com.intellij.util.ReflectionUtil;
import com.intellij.util.ThrowableRunnable;
import org.jetbrains.annotations.NotNull;

public abstract class WriteAction<T> extends BaseActionRunnable<T> {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.application.WriteAction");

  @NotNull
  @Override
  @SuppressWarnings("InstanceofCatchParameter")
  public RunResult<T> execute() {
    final RunResult<T> result = new RunResult<T>(this);

    final Application application = ApplicationManager.getApplication();
    boolean dispatchThread = application.isDispatchThread();
    if (dispatchThread) {
      AccessToken token = start(getClass());
      try {
        result.run();
      } finally {
        token.finish();
      }
      return result;
    }

    if (application.isReadAccessAllowed()) {
      LOG.error("Must not start write action from within read action in the other thread - deadlock is coming");
    }

    application.invokeAndWait(new Runnable() {
      @Override
      public void run() {
        AccessToken transaction = TransactionGuard.getInstance().startSynchronousTransaction(TransactionKind.ANY_CHANGE);
        try {
          AccessToken token = start(WriteAction.this.getClass());
          try {
            result.run();
          }
          finally {
            token.finish();
          }
        }
        finally {
          transaction.finish();
        }
      }
    }, ModalityState.defaultModalityState());

    result.throwException();
    return result;
  }

  @NotNull
  public static AccessToken start() {
    // get useful information about the write action
    Class aClass = ObjectUtils.notNull(ReflectionUtil.getGrandCallerClass(), WriteAction.class);
    return start(aClass);
  }

  @NotNull
  public static AccessToken start(@NotNull Class clazz) {
    return ApplicationManager.getApplication().acquireWriteActionLock(clazz);
  }

  public static <E extends Throwable> void runWriteAction(@NotNull ThrowableRunnable<E> action) throws E {
    AccessToken token = start();
    try {
      action.run();
    } finally {
      token.finish();
    }
  }

  public static <T, E extends Throwable> T runWriteAction(@NotNull ThrowableComputable<T, E> action) throws E {
    return ApplicationManager.getApplication().runWriteAction(action);
  }
}
