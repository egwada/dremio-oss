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

import { rest } from "msw";
import { sampleSources } from "./sampleSources";
import { getSourcesUrl } from "../listSources";

const originalMockData = sampleSources;
let mockData = originalMockData;

export const setMockData = (newMockData: any) => {
  mockData = newMockData;
};

export const restoreMockData = () => {
  mockData = originalMockData;
};

export const listSourcesHandler = rest.get(
  decodeURIComponent(
    getSourcesUrl(true).replace(`//${window.location.host}`, ""),
  ),
  (req, res, ctx) => {
    return res(ctx.delay(200), ctx.json(mockData));
  },
);

export const listSourcesHandler2 = rest.get(
  decodeURIComponent(
    getSourcesUrl(false).replace(`//${window.location.host}`, ""),
  ),
  (req, res, ctx) => {
    return res(ctx.delay(200), ctx.json(mockData));
  },
);
