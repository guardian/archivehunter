import React from "react";
import {ColDef} from "@material-ui/data-grid";
import FilterButton from "../common/FilterButton";
import JobTypeIcon from "./JobTypeIcon";
import TimestampFormatter from "../common/TimestampFormatter";
import JobStatusIcon from "./JobStatusIcon";
import ResubmitComponent from "./ResubmitComponent";
import {Button, IconButton, Tooltip} from "@material-ui/core";
import {Launch} from "@material-ui/icons";

function makeJobsListColumns(filterUpdated: (fieldName:string, values:string, type:"add"|"remove")=>void,
                             openLog: (logContent:string)=>void,
                             openItem: (itemId:string)=>void,
                             showRelativeTimes: boolean):ColDef[] {
    return [
        {
            field: "jobId",
            headerName: "ID",
            sortable: false,
        },
        {
            field: "jobType",
            headerName: "Type",
            renderCell: (params) => <span>
                        <FilterButton fieldName="jobType"
                                      values={params.value}
                                      type="plus"
                                      onActivate={()=>params.value ? filterUpdated("jobType", params.value as string,"add") : null}
                        />
                        <FilterButton fieldName="jobType"
                                      values={params.value}
                                      type="minus"
                                      onActivate={()=>params.value ? filterUpdated("jobType", params.value as string,"remove") : null}/>
                        <JobTypeIcon jobType={params.value}/>
                    </span>
        },
        {
            field: "startedAt",
            headerName: "Start Time",
            width: 200,
            renderCell: (params)=><TimestampFormatter relative={showRelativeTimes} value={params.value as string}/>
        },
        {
            field: "completedAt",
            headerName: "Completion Time",
            width: 200,
            renderCell: (params)=><TimestampFormatter relative={showRelativeTimes} value={params.value as string}/>
        },
        {
            field: "status",
            headerName: "Status",
            renderCell: (params)=><span>
                        <FilterButton fieldName="jobStatus"
                                      values={params.value}
                                      type="plus"
                                      onActivate={()=>params.value ? filterUpdated("status", params.value as string,"add") : null}/>
                        <FilterButton fieldName="jobStatus"
                                      values={params.value}
                                      type="minus"
                                      onActivate={()=>params.value ? filterUpdated("status", params.value as string,"remove") : null}/>
                        <JobStatusIcon status={params.value}/>
                </span>
        },
        {
            field: "log",
            headerName: "Log",
            width: 200,
            renderCell: (params)=> (!params.value || params.value==="") ? <p>None</p> :
                <a style={{cursor: "pointer"}} onClick={()=>openLog(params.value as string)}>View</a>
        },
        {
            field: "resubmitBtn",
            headerName: "Resubmit",
            width: 150,
            renderCell: (params)=>{
                const maybeJobType = params.getValue("jobType");
                return <ResubmitComponent jobId={params.value} visible={maybeJobType}/>
            }
        },
        {
            field: "sourceId",
            headerName: "Source file",
            width: 400,
            renderCell: (params)=><span>
                        <FilterButton fieldName="sourceId"
                                      values={params.value}
                                      type="plus"
                                      onActivate={params.value ? filterUpdated("sourceId", params.value as string,"add") : null}/>
                        <FilterButton fieldName="sourceId"
                                      values={params.value}
                                      type="minus"
                                      onActivate={params.value ? filterUpdated("sourceId", params.value as string,"remove") : null}/>
                        {/*<Link to={"/browse?open="+params.value}>View</Link>*/}
                        <Tooltip title="View source file in Browse">
                            <IconButton onClick={()=>openItem(params.value as string)}>
                                <Launch/>
                            </IconButton>
                        </Tooltip>
                </span>
        },
        {
            field: "sourceType",
            width: 200,
            headerName: "Source type"
        }
    ];
}

export {makeJobsListColumns};