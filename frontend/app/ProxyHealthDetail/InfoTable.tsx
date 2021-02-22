import React from "react";
import {ColDef, DataGrid} from "@material-ui/data-grid";
import ThreeWayIcon from "./ThreeWayIcon";
import {makeStyles, Paper} from "@material-ui/core";
import {ProblemItemRow, ProxyVerifyResult} from "../types";
import {Link} from "react-router-dom";
import AttemptRetry from "./AttemptRetry";

interface InfoTableProps {
    tableData: ProblemItemRow[];
}

const renderResult = (props:ProxyVerifyResult)=> <span>
    <ThreeWayIcon iconName="check" title={props.haveProxy ? "Proxy exists" : "Proxy absent"} state={props.haveProxy} onColour="green" hide={!props.wantProxy}/>
    <ThreeWayIcon iconName="exclamation" title={props.wantProxy ? "Need proxy" : "Don't need this proxy"} state={props.wantProxy} onColour="orange" hide={props.haveProxy}/>
    <ThreeWayIcon iconName="unlink" title="Can't find existing proxy" state={props.known} onColour="black" hide={props.known}/>
</span>;

const infoTableColumns:ColDef[] = [
    {
        field: "collection",
        headerName: "Collection",
        width: 200
    },
    {
        field: "fileName",
        headerName: "Filename",
        width: 200
    },
    {
        field: "path",
        headerName: "Path",
        width: 300
    },
    {
        field: "thumbnailResult",
        headerName: "Thumbnail",
        renderCell: (params)=>renderResult((params.value as ProblemItemRow).thumbnailResult)
    },
    {
        field: "videoResult",
        headerName: "Video",
        renderCell: (params)=>renderResult(params.value as ProxyVerifyResult)
    },
    {
        field: "audioResult",
        headerName: "Audio",
        renderCell: (params)=>renderResult(params.value as ProxyVerifyResult)
    },
    {
        headerName: "Index status",
        field: "esRecordSays",
        /* it's an error if this is TRUE, because that would mean ES thinks that there IS a proxy but actually there isn't one. */
        renderCell: (params)=><span>
                        <ThreeWayIcon iconName="check" state={!(params.value as boolean)} onColour="red" hide={!(params.value as boolean)}/>
                        <ThreeWayIcon iconName="times" state={(params.value as boolean)} onColour="green" hide={(params.value as boolean)}/>
                    </span>
    },
    {
        headerName: "Jobs",
        field: "joblist",
        renderCell: (params)=><Link to={"/admin/jobs?sourceId=" + encodeURIComponent(params.getValue("fileId") as string)}>View jobs...</Link>,
        width: 300
    },
    {
        headerName: "Retry",
        field: "fileId",
        renderCell: (params)=> {
            const itemId = params.getValue("fileId") as string;
            const videoResult = params.getValue("videoResult") as ProxyVerifyResult;
            const audioResult = params.getValue("audioResult") as ProxyVerifyResult;
            const thumbnailResult = params.getValue("thumbnailResult") as ProxyVerifyResult;

            return <AttemptRetry itemId={itemId}
                          haveVideo={videoResult.haveProxy || !videoResult.wantProxy}
                          haveAudio={audioResult.haveProxy || !audioResult.wantProxy}
                          haveThumb={thumbnailResult.haveProxy || !thumbnailResult.wantProxy}/>
        }
    }
];

const useStyles = makeStyles({
    tableContainer: {
        height: "50vh",
    }
});

const InfoTable:React.FC<InfoTableProps> = (props) => {
    const classes = useStyles();
    return <Paper elevation={3} className={classes.tableContainer}>
        <DataGrid columns={infoTableColumns} rows={props.tableData} />
    </Paper>
}

export default InfoTable;