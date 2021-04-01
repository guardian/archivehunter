import React, {useEffect, useState} from "react";
import {ColDef, DataGrid} from "@material-ui/data-grid";
import PathDisplayComponent from "../browse/PathDisplayComponent";
import {ArchiveEntry} from "../types";
import FileSizeView from "../Entry/FileSizeView";
import TimestampFormatter from "../common/TimestampFormatter";
import {Grid, IconButton, Tooltip} from "@material-ui/core";
import {DeleteForever} from "@material-ui/icons";

interface DeletedItemsTableProps {
    entries: ArchiveEntry[];
    requestDelete: (itemId:string)=>void;
}

// const DeletedItemsTable:React.FC<DeletedItemsTableProps> = (props) => {
//     const
//     return React.memo(DeletedItemsTableContent, (prevProps, nextProps)=>{
//
//     })
// }

const DeletedItemsTable:React.FC<DeletedItemsTableProps> = (props) => {
    const columns:ColDef[] = [
        {
            field: "bucket",
            headerName: "Collection",
            renderCell: (params)=><Tooltip title={params.value as string}>
                <p>{params.value as string}</p>
            </Tooltip>
        },
        {
            field: "path",
            headerName: "Path",
            width: 500,
            //renderCell: (params)=><PathDisplayComponent path={params.getValue("path") as string}/>
        },
        {
            field: "size",
            headerName: "Size",
            renderCell: (params)=><FileSizeView rawSize={params.getValue("size") as number}/>
        },
        {
            field: "storageClass",
            headerName: "Storage Class",
            width: 150
        },
        {
            field: "last_modified",
            headerName: "Last Modified",
            renderCell: (params)=><TimestampFormatter relative={false} value={params.getValue("last_modified") as string}/>,
            width: 250
        },
        {
            field: "proxied",
            headerName: "Proxied?",
        },
        {
            field: "id",
            headerName: " ",
            renderCell: (params)=><Grid container direction="row">
                <Grid item>
                    <Tooltip title="Remove this tombstone">
                        <IconButton onClick={()=>props.requestDelete(params.value as string)}>
                            <DeleteForever/>
                        </IconButton>
                    </Tooltip>
                </Grid>
            </Grid>
        }
    ];

    return <DataGrid columns={columns}
                     rows={props.entries}
    />
}

export default DeletedItemsTable;