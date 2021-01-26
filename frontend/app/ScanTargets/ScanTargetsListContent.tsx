import React from "react";
import { DataGrid, ColDef, ValueGetterParams } from "@material-ui/data-grid";
import {IconButton, Link, Typography} from "@material-ui/core";
import {DeleteForever} from "@material-ui/icons";
import {Link as RouterLink} from "react-router-dom";
import TickCrossIcon from "../common/TickCrossIcon";
import TimestampFormatter from "../common/TimestampFormatter.jsx";
import TimeIntervalComponent from "../common/TimeIntervalComponent.jsx";
import {TranscoderCheck} from "../types";
import TranscoderCheckComponent from "./TranscoderCheckComponent.jsx";

function makeScanTargetColumns(deletionCb:(targetName:string)=>void): ColDef[] {
    return [
        { field: "id", headerName: "  ", sortable: false, width: 60, filterable: false,
            renderCell: (params)=><IconButton style={{width: "50px"}} onClick={()=>{
                const bucketNameCellValue = params.getValue("bucketName");
                bucketNameCellValue ? deletionCb(bucketNameCellValue as string) : null;
            }}><DeleteForever/></IconButton>
        },
        { field: "bucketName", headerName: "Bucket Name", width: 200,
            renderCell: (params)=>
                <Link component={RouterLink} to={`scanTargets/${params.getValue("bucketName")}`}>{params.getValue("bucketName")}</Link>
        },
        { field: "region", headerName: "Region", width: 100,
            renderCell: (params)=>
                <Typography>{params.getValue("region")}</Typography>
        },
        { field: "enabled", headerName: "Enabled", width: 40,
            renderCell: (params)=>{
                const value = params.getValue("enabled");
                return value ? <TickCrossIcon value={value as boolean}/> : <span/>;
        }},
        { field: "lastScanned", headerName: "Last Scanned", width: 200,
            renderCell: (params) => {
                const value = params.getValue("lastScanned");
                return value ? <TimestampFormatter relative={true} value={value}/> : <Typography>Never scanned</Typography>
            }
        },
        { field: "scanInterval", headerName: "Scan Interval", width: 200,
            renderCell: (params)=>{
                const value = (params.getValue("scanInterval") as number|undefined) ?? 0;
                return <TimeIntervalComponent editable={false} value={value}/>
            }
        },  //needs to interpret data via TimestampFormatter
        { field: "scanInProgress", headerName: "Currently Scanning", width: 150},   //needs something wizzy
        { field: "lastError", headerName: "Last scan error", width: 200},
        { field: "proxyBucket", headerName: "Proxy Bucket", width: 200},
        { field: "transcoderCheck", headerName: "Transcoder Check", width: 200,
        renderCell: (params)=> {
            const transcoderCheckInfo = params.value as TranscoderCheck|undefined|null;
            return transcoderCheckInfo ? <TranscoderCheckComponent status={transcoderCheckInfo.status}
                                                                   checkedAt={transcoderCheckInfo.checkedAt}
                                                                   log={transcoderCheckInfo.log}/>

                                                                   : <Typography>Not checked</Typography>
        }},
        { field: "pendingJobIds", headerName: "Pending Jobs", width: 100,
            valueFormatter: (params)=> {
                const jobIdList = params.value as string[]|undefined|null;
                return jobIdList ? jobIdList.length : "None";
            }
        },
    ];
}

export {makeScanTargetColumns};