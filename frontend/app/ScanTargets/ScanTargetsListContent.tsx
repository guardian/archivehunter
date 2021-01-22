import React from "react";
import { DataGrid, ColDef, ValueGetterParams } from "@material-ui/data-grid";

const scanTargetColumns: ColDef[] = [
    { field: "bucketName", headerName: "Delete"},   //needs an IconButton
    { field: "bucketName", headerName: "Bucket"},   //needs a Link
    { field: "region", headerName: "Region"},
    { field: "enabled", headerName: "Enabled"},     //needs a checkbox
    { field: "lastScanned", headerName: "Last Scanned"},   //needs to interpret data via TimestampFormatter
    { field: "scanInterval", headerName: "Scan Interval"},  //needs to interpret data via TimestampFormatter
    { field: "scanInProgress", headerName: "Currently Scanning"},   //needs something wizzy
    { field: "lastError", headerName: "Last scan error"},
    { field: "proxyBucket", headerName: "Proxy Bucket"},
    { field: "transcoderCheck", headerName: "Transcoder Check"},    //needs to render TranscoderCheckcomponent
    { field: "Pending jobs", headerName: "pendingJobIds"},
];

export {scanTargetColumns};