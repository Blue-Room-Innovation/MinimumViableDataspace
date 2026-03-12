#
#  Copyright (c) 2023 Contributors to the Eclipse Foundation
#
#  See the NOTICE file(s) distributed with this work for additional
#  information regarding copyright ownership.
#
#  This program and the accompanying materials are made available under the
#  terms of the Apache License, Version 2.0 which is available at
#  https://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
#  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
#  License for the specific language governing permissions and limitations
#  under the License.
#
#  SPDX-License-Identifier: Apache-2.0
#

variable "consumer-did" {
  default = "did:web:consumer-identityhub%3A7083:consumer"
}

variable "provider-did" {
  default = "did:web:provider-identityhub%3A7083:provider"
}

variable "useSVE" {
  type        = bool
  description = "If true, the -XX:UseSVE=0 switch (Scalable Vector Extensions) will be added to the JAVA_TOOL_OPTIONS. Can help on macOs on Apple Silicon processors"
  default     = false
}

variable "anonymization-enabled" {
  type        = bool
  description = "Enable k-anonimization extension in provider dataplanes"
  default     = true
}

variable "anonymization-service-url" {
  type        = string
  description = "Base URL of anonymization service consumed by provider dataplanes"
  default     = "http://host.docker.internal:8880/api/secure/"
}

variable "anonymization-service-api-key-id" {
  type        = string
  description = "API key id for anonymization service"
  default     = "f56bf3015dedc9c7a25921260b98e389"
}

variable "anonymization-service-api-key-secret" {
  type        = string
  description = "API key secret for anonymization service"
  default     = "5e321465d4029814ee004faefa3ab4f3f8aee41f50ad2dfe05b5ac4363286ea8"
}

variable "anonymization-timeout-seconds" {
  type        = number
  description = "Timeout in seconds for anonymization HTTP interactions"
  default     = 300
}