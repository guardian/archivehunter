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

interface ObjectMapResponse<T> {
    status: string;
    entityClass: string;
    entries: T;
    entryCount: number;
}

type ObjectListResponse<T> = ObjectMapResponse<T[]>;

interface ObjectGetResponse<T> {
    status: string;
    objectClass: string;
    entry: T;
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


interface ProxyVerifyResult {
    fileId: string;
    proxyType: "VIDEO"|"AUDIO"|"THUMBNAIL"|"UNKNOWN";
    wantProxy: boolean;
    esRecordSays: boolean;
    haveProxy?: boolean;
    known?:boolean;
}

interface ProblemItem {
    fileId: string;
    collection: string;
    filePath: string;
    esRecordSays: boolean;
    verifyResults: ProxyVerifyResult[]
    decision?:  "Proxied"|"Partial"|"Unproxied"|"NotNeeded"|"DotFile"|"GlacierClass";
}

type ProblemItemResponse = ObjectListResponse<ProblemItem>;
type ProblemItemRow = ProblemItem & {id: number, thumbnailResult: ProxyVerifyResult, videoResult: ProxyVerifyResult, audioResult: ProxyVerifyResult};

/*
q: null,
                path: pathToSearch,
                collection: this.state.collectionName,
                sortBy: this.state.sortField,
                sortOrder: this.state.sortOrder,
                hideDotFiles: !this.state.showDotFiles
            }
 */
interface AdvancedSearchDoc {
    q?: string;
    path?: string;
    collection: string;
    sortBy?: string; //field name to sort by
    sortOrder?: SortOrder;
    hideDotFiles?: boolean;
}

interface MimeType {
    major: string;
    minor: string;
}

interface LightboxIndex {
    owner: string;
    avatarUrl?: string;
    addedAt: string;    //ISO datetime string
    memberOfBulk?: string;
}

interface MediaFormat {
    tags: Record<string,string>;
    nb_streams: number;
    start_time?: number;
    format_long_name: string;
    format_name: string;
    bit_rate: number;
    nb_programs: number;
    duration: number;
    size: number;
}

interface MediaMetadata {
    format: MediaFormat;
    streams: any[]; //sorry but i can't be bothered to type out the MediaStreams definition
}

/*
id:String, bucket: String, path: String, region:Option[String], file_extension: Option[String], size: scala.Long, last_modified: ZonedDateTime,
etag: String, mimeType: MimeType, proxied: Boolean, storageClass:StorageClass, lightboxEntries:Seq[LightboxIndex], beenDeleted:Boolean=false,
 mediaMetadata:Option[MediaMetadata]
 */
interface ArchiveEntry {
    id: string;
    bucket: string;
    path: string;
    region?: string;
    file_extension?: string;
    size: number;
    last_modified: string;
    etag: string;
    mimeType: MimeType;
    proxied: boolean;
    storageClass: "STANDARD"|"STANDARD_IA"|"GLACIER"|"REDUCED_REDUNDANCY";
    lightboxEntries: LightboxIndex[];
    beenDeleted: boolean;
    mediaMetadata?: MediaMetadata;
}

type SearchResponse = ObjectListResponse<ArchiveEntry>;
type ArchiveEntryResponse = ObjectGetResponse<ArchiveEntry>;

type CollectionNamesResponse = ObjectListResponse<string>;

type SortOrder = "Ascending"|"Descending";
type SortableField = "path"|"last_modified"|"size";

type BrowseDirectoryResponse = ObjectListResponse<string>;

//used in the tree view for a single directory entry
interface PathEntry {
    name: string;
    fullpath: string;
    idx: number;
}

interface UserDetails {
    firstName?: string;
    lastName?: string;
    email: string;
    avatarUrl?: string;
    isAdmin: boolean;
}

type UserDetailsResponse = UserDetails; //this response is not in a wrapper

/*
(id: String, description: String, userEmail: String, addedAt: ZonedDateTime, errorCount: Int, availCount: Int, restoringCount: Int)
 */
interface LightboxBulk {
    id: string;
    description: string;
    userEmail: string;
    addedAt: string;
    errorCount: number;
    availCount: number;
    restoringCount: number;
}

type LightboxBulkResponse = ObjectListResponse<LightboxBulk>;

type RestoreStatus = "RS_UNNEEDED"|"RS_ALREADY"|"RS_PENDING"|"RS_UNDERWAY"|"RS_SUCCESS"|"RS_ERROR";

interface LightboxEntry {
    userEmail: string;
    fileId: string;
    addedAt: string;
    restoreStatus: RestoreStatus;
    restoreStarted?: string;
    restoreCompleted?: string;
    availableUntil?: string;
    lastError?: string;
    memberOfBulk?: string;
}

type LightboxDetailsResponse = ObjectMapResponse<Record<string, LightboxEntry>>;

/*
status:String, fileId: String, restoreStatus:RestoreStatus.Value, expiry:Option[ZonedDateTime], downloadLink:Option[String]
 */
interface RestoreStatusResponse {
    status: string;
    fileId: string;
    restoreStatus: RestoreStatus;
    expiry?: string;
    downloadLink?: string;
}