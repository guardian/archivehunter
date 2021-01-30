import {ReactText} from "react";
import {RowModel} from "@material-ui/data-grid";

/**
 * this is used when combining a static object with the baseStyles using Object.assign().
 * when calling `makeStyles()` with a static object it's not necessary, but when calling with a
 * function to inject the theme you need to force the static object like so:
 * makeStyles( (theme:Theme) => Object.assign({myStyles} as StylesMap, baseStyles));
 */
interface StylesMap {
    [name:string]: any
}

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

interface UserProfile {
    adminAuthQuota?: number;
    adminRollingAuthQuota?: number;
    allCollectionsVisible: boolean;
    department?: string;
    isAdmin?: boolean;
    perRestoreQuota?: number;
    productionOffice?: string;
    rollingRestoreQuota?: number;
    userEmail: string;
    visibleCollections: string[];
}

//this is used in the UserList UI to represent a table row containing UserProfile objects
type UserProfileRow = RowModel & UserProfile;

/**
 * see ProxyFrameworkInstance in cmn_models
 * region:String, inputTopicArn:String, outputTopicArn:String, roleArn:String, subscriptionId:Option[String]
 */
interface ProxyFrameworkDeployment {
    region: string;
    inputTopicArn: string;
    outputTopicArn: string;
    roleArn: string;
    subscriptionId?: string;
}

type ProxyFrameworkDeploymentsResponse = ObjectListResponse<ProxyFrameworkDeployment>;
type ProxyFrameworkDeploymentRow = ProxyFrameworkDeployment & {id: string};

interface RegionScanError {
    region: string;
    error: string;
}

interface MultiResultResponse<T,V> {
    status: string;
    entityClass: string;
    success: T[];
    failure: V[];
}

interface ProxyFrameworkStack {
    region: string;
    stackId: string;
    stackName: string;
    stackStatus: string;
    templateDescription: string;
    creationTime: string;
}

type ProxyFrameworkStackRow = ProxyFrameworkStack & {id: number};

//this response contains an array of arrays; the outer array is for each region and the inner array is the deployments for that region.
type ProxyFrameworkSearchResponse = MultiResultResponse<ProxyFrameworkStack[], string[]>;

interface ProxyFrameworkManualConnection {
    inputTopic: string;
    replyTopic: string;
    managementRole: string;
}

interface ProxyFrameworkAutoConnection {
    region: string;
    stackName: string;
    uuid: string;
}