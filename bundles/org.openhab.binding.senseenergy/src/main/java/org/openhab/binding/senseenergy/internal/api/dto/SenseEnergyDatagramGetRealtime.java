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
package org.openhab.binding.senseenergy.internal.api.dto;

import com.google.gson.annotations.SerializedName;

/**
 * The {@link SenseEnergyDatagramGetRealtime } dto
 *
 * @author Jeff James - Initial contribution
 */
public class SenseEnergyDatagramGetRealtime {
    public float current;
    public float voltage;
    public float power;
    public int total;
    @SerializedName("err_code")
    public int errorCode;
}
