/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.boschindego.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * {@link DeviceStateAttribute} describes a characteristic for a device state.
 * 
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public enum DeviceStateAttribute {
    READY_TO_MOW,
    DOCKED,
    CHARGING,
    ACTIVE,
    COMPLETED
}
