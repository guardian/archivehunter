import {ReactText} from "react";

interface ObjectListResponse<T> {
    status: string;
    entityClass: string;
    entries: T[];
    entryCount: number;
}

type JobStatus = "ST_PENDING"|"ST_RUNNING"|"ST_SUCCESS"|"ST_ERROR"|"ST_CANCELLED"|"ST_WARNING";

interface TranscoderCheck {
    checkedAt: string;  //ISO timestamp
    status: JobStatus;
    log: string|null;
}

interface ScanTarget {
    id: ReactText;       //added by the frontend
    bucketName: string;
    enabled: boolean;
    lastScanned: string|null;   //optional ISO timestamp
    scanInterval: number;
    scanInProgress:boolean;
    lastError: string|null;
    proxyBucket:string;
    region:string;
    pendingJobIds:string[]|null;
    transcoderCheck:TranscoderCheck|null;
    paranoid: boolean|null;
    proxyEnabled: boolean|null;
}

type ScanTargetResponse = ObjectListResponse<ScanTarget>;