/*
 * Copyright (C) 2017-2019 Dremio Corporation
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

import { isDataPlaneSourceType } from "@app/constants/sourceTypes";
import StorageSelectionWrapper from "@app/components/Forms/Wrappers/StorageSelectionWrapper";
import OauthWrapper from "@app/components/Forms/Wrappers/OauthWrapper";

export const getRendererOverride = (sourceType: string, propName: string) => {
  if (isDataPlaneSourceType(sourceType)) {
    switch (propName) {
      case "config.storageProvider":
        return StorageSelectionWrapper;
      case "config.nessieAuthType":
        return OauthWrapper;
      default:
        return;
    }
  }
};
