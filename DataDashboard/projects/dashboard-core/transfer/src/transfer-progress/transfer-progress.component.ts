/*
 *  Copyright (c) 2025 Fraunhofer-Gesellschaft zur Förderung der angewandten Forschung e.V.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Fraunhofer-Gesellschaft zur Förderung der angewandten Forschung e.V. - initial API and implementation
 *
 */

import { Component, Input, OnChanges, OnDestroy, inject } from '@angular/core';
import {
  compact,
  ContractAgreement,
  ContractNegotiation,
  IdResponse,
  TransferProcess,
  TransferProcessStates,
} from '@think-it-labs/edc-connector-client';
import { ContractAndTransferService } from '../contract-and-transfer.service';
import { NgClass } from '@angular/common';
import { TransferPullDownloadComponent } from '../transfer-pull-download/transfer-pull-download.component';
import { ModalAndAlertService } from '@eclipse-edc/dashboard-core';

@Component({
  selector: 'lib-transfer-progress',
  templateUrl: './transfer-progress.component.html',
  styleUrl: './transfer-progress.component.css',
  imports: [NgClass, TransferPullDownloadComponent],
})
export class TransferProgressComponent implements OnChanges, OnDestroy {
  private static readonly TRACE_PREFIX = '[TransferProgressTrace]';

  private readonly transferService = inject(ContractAndTransferService);
  private readonly modalAndAlertService = inject(ModalAndAlertService);

  @Input() agreement!: ContractAgreement;
  @Input() negotiation!: ContractNegotiation;
  @Input() transferId!: IdResponse;
  @Input() pullIntervalMs = 500;

  polling = true;
  process?: TransferProcess;
  type?: 'Push' | 'Pull';
  currentState?: TransferProcessStates;
  stateHistory: TransferProcessStates[] = [];
  happyPathStates: TransferProcessStates[] = [
    TransferProcessStates.INITIAL,
    TransferProcessStates.PROVISIONED,
    TransferProcessStates.REQUESTED,
    TransferProcessStates.STARTED,
    TransferProcessStates.COMPLETED,
  ];
  happyPath = true;
  exceptionStates: TransferProcessStates[] = [
    TransferProcessStates.SUSPENDED,
    TransferProcessStates.TERMINATED,
    TransferProcessStates.DEPROVISIONED,
  ];

  errorMsg?: string;
  successMsg?: string;
  debugLines: string[] = [];

  private statusJob?: ReturnType<typeof setInterval>;
  private statusRequestInFlight = false;
  private completionHandled = false;

  private getTransferProcessId(): string | undefined {
    return (
      (this.transferId as IdResponse & { ['@id']?: string }).id ??
      (this.transferId as IdResponse & { ['@id']?: string })['@id']
    );
  }

  get resolvedTransferProcessId(): string | undefined {
    return this.getTransferProcessId();
  }

  async ngOnChanges() {
    const transferProcessId = this.getTransferProcessId();
    if (transferProcessId) {
      this.resetProgressState();
      this.process = await compact(await this.transferService.getTransferProcess(transferProcessId));

      if (this.process) {
        this.type = this.resolveTransferType(this.process);
        if (this.type === 'Pull') {
          if (this.happyPathStates.includes(TransferProcessStates.COMPLETED)) {
            this.happyPathStates.pop();
          }
        }
        this.currentState = this.process.state as TransferProcessStates;
        if (this.stateHistory.length === 0) {
          this.stateHistory.push(this.currentState);
        }
      }
      this.stopStatusJob();
      this.startStatusJob();
    }
  }

  startStatusJob(): void {
    this.statusJob = setInterval(this.pullStatus.bind(this), this.pullIntervalMs);
    this.polling = true;
  }

  stopStatusJob(): void {
    if (this.statusJob) {
      clearInterval(this.statusJob);
      this.statusJob = undefined;
    }
    this.polling = false;
  }

  private async pullStatus() {
    if (this.statusRequestInFlight) {
      return;
    }

    this.statusRequestInFlight = true;
    const transferProcessId = this.getTransferProcessId();
    if (!transferProcessId) {
      this.errorMsg = 'Transfer process id is missing.';
      this.stopStatusJob();
      this.statusRequestInFlight = false;
      return;
    }

    try {
      this.process = await compact(await this.transferService.getTransferProcess(transferProcessId));
      this.currentState = this.process?.state as TransferProcessStates;
      this.type = this.resolveTransferType(this.process);

      if (
        // First or new state
        (this.stateHistory.length === 0 || this.stateHistory[this.stateHistory.length - 1] !== this.currentState) &&
        // Unknown states are ignored
        (this.happyPathStates.includes(this.currentState) || this.exceptionStates.includes(this.currentState))
      ) {
        // Non-normal path
        if (this.exceptionStates.includes(this.currentState)) {
          this.stopStatusJob();
          this.happyPath = false;
          this.stateHistory.push(this.currentState);
          this.process = await compact(await this.transferService.getTransferProcess(transferProcessId));
          this.errorMsg = JSON.stringify(this.process);
        } else {
          // Include missed states due to pull mechanism
          this.stateHistory = this.happyPathStates.slice(0, this.happyPathStates.indexOf(this.currentState) + 1);
        }
      }
      // Stop for happy path end states
      if (
        (this.type === 'Pull' && this.currentState === TransferProcessStates.STARTED) ||
        this.currentState === TransferProcessStates.COMPLETED
      ) {
        this.stopStatusJob();
        this.handleCompletedState();
      }
    } catch (error) {
      console.error(`${TransferProgressComponent.TRACE_PREFIX} pullStatus:error`, error);
    } finally {
      this.statusRequestInFlight = false;
    }
  }

  closeModal(): void {
    this.modalAndAlertService.closeModal();
  }

  private handleCompletedState(): void {
    if (this.currentState !== TransferProcessStates.COMPLETED || this.type !== 'Push' || this.completionHandled) {
      return;
    }

    this.completionHandled = true;
    this.successMsg = 'Push transfer completed successfully.';
    this.modalAndAlertService.showAlert(this.successMsg, undefined, 'success', 6);
    setTimeout(() => this.closeModal(), 800);
  }

  private resolveTransferType(process?: TransferProcess): 'Push' | 'Pull' | undefined {
    const transferType =
      process?.['transferType'] ??
      process?.mandatoryValue?.<string>('edc', 'transferType') ??
      process?.['https://w3id.org/edc/v0.0.1/ns/transferType'];

    if (!transferType) {
      return this.type;
    }
    return transferType.toLowerCase().includes('push') ? 'Push' : 'Pull';
  }

  private resetProgressState(): void {
    this.stopStatusJob();
    this.polling = true;
    this.process = undefined;
    this.type = undefined;
    this.currentState = undefined;
    this.stateHistory = [];
    this.happyPath = true;
    this.errorMsg = undefined;
    this.successMsg = undefined;
    this.completionHandled = false;
    this.statusRequestInFlight = false;
    this.happyPathStates = [
      TransferProcessStates.INITIAL,
      TransferProcessStates.PROVISIONED,
      TransferProcessStates.REQUESTED,
      TransferProcessStates.STARTED,
      TransferProcessStates.COMPLETED,
    ];
  }

  ngOnDestroy(): void {
    this.stopStatusJob();
  }

  private describeProcess(process?: TransferProcess) {
    if (!process) {
      return { process: undefined };
    }

    return {
      id: process.id,
      state: process.state,
      type: process.type,
      transferType: process['transferType'],
      edcTransferType: process['https://w3id.org/edc/v0.0.1/ns/transferType'],
      resolvedType: this.type,
      currentState: this.currentState,
      stateHistory: this.stateHistory,
      polling: this.polling,
    };
  }

  private trace(event: string, payload?: unknown): void {
    const message = `${TransferProgressComponent.TRACE_PREFIX} ${event}`;

    const renderedPayload = this.stringifyPayload(payload);
    this.debugLines = [...this.debugLines.slice(-11), `${message}${renderedPayload ? ` ${renderedPayload}` : ''}`];
  }

  private stringifyPayload(payload?: unknown): string {
    if (payload === undefined) {
      return '';
    }

    try {
      return JSON.stringify(payload);
    } catch {
      return String(payload);
    }
  }

  protected readonly TransferProcessStates = TransferProcessStates;
}
