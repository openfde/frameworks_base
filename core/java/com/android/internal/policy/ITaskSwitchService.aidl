/*
 * Copyright (C) 2026 OpenFDE
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

package com.android.internal.policy;


/**
* An interface to let the system (PhoneWindowManager) trigger a left/right
* task switch between fullscreen tasks and desks.
* @hide
*/
interface ITaskSwitchService {

    /**
     * Switches to the neighboring item of the current focused task/desk in the
     * "recency ring" of fullscreen tasks + desks.
     *
     * @param displayId display id of the key event.
     * @param direction -1 switch to the previous (left) item,
     *                  +1 switch to the next (right) item.
     */
    void performTaskSwitch(int displayId, int direction);
}
