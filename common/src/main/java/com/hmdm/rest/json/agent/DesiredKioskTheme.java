/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/**
 * Visual theme for {@link DesiredKiosk}: background/text colors and launcher icon size, plus the MeinConnect
 * branding fields (title, logo, accent, background image, brand bar). Every field is optional; the agent
 * falls back to its built-in brand defaults for anything absent.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredKioskTheme {
    private String backgroundColor;
    private String textColor;
    private String iconSize;
    // MeinConnect fork
    private String title;
    private String logoUrl;
    private String accentColor;
    private String backgroundImageUrl;
    /** {@code none} or colour stops {@code #RRGGBB:END%,…} drawn as a gradient, e.g. {@code #0957c3:40,#593c90:64,#aa205d:84,#fa052a:100}. */
    private String brandBar;
}
