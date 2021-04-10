import React, {useEffect, useRef, useState} from "react";
import {ColDef, DataGrid} from "@material-ui/data-grid";
import PathDisplayComponent from "../browse/PathDisplayComponent";
import {ArchiveEntry} from "../types";
import FileSizeView from "../Entry/FileSizeView";
import TimestampFormatter from "../common/TimestampFormatter";
import {Grid, IconButton, makeStyles, Tooltip} from "@material-ui/core";
import {DeleteForever, Launch} from "@material-ui/icons";

interface DeletedItemsTableProps {
    entries: ArchiveEntry[];
    requestDelete: (itemId:string)=>void;
    currentlyLoading: boolean;
    requestOpen: (itemId:string)=>void;
}

/**
 * wrapper that only re-renders the table every 30 updates, to prevent slowing down the browser
 * @param props
 * @constructor
 */
const DeletedItemsTable:React.FC<DeletedItemsTableProps> = (props:DeletedItemsTableProps) => {
    const [unrenderedItemCount, setUnrenderedItemCount] = useState(0);
    const unrenderedItemCountRef = useRef<number>();
    unrenderedItemCountRef.current = unrenderedItemCount;

    const WrappedComponent = React.memo(DeletedItemsTableContent, (prevProps, nextProps)=>{
        if(!nextProps.currentlyLoading) {   //perform the update always if the state we are moving to is not a loading state
            return false
        }

        if(unrenderedItemCountRef.current && unrenderedItemCountRef.current>30) {
            setUnrenderedItemCount(0);
            return false;
        } else {
            setUnrenderedItemCount((prev)=>prev+1);
            return true;
        }
    })

    return <WrappedComponent {...props}/>
}

const useStyles = makeStyles({
    ellipsizedText: {
        overflow: "hidden",
        textOverflow: "ellipsis"
    }
});

const DeletedItemsTableContent:React.FC<DeletedItemsTableProps> = (props) => {
    const classes = useStyles();

    const columns:ColDef[] = [
        {
            field: "bucket",
            headerName: "Collection",
            renderCell: (params)=><Tooltip title={params.value as string}>
                <p className={classes.ellipsizedText}>{params.value as string}</p>
            </Tooltip>
        },
        {
            field: "path",
            headerName: "Path",
            width: 500,
            renderCell: (params)=><Tooltip title={params.value as string}><p className={classes.ellipsizedText}>{params.value as string}</p></Tooltip>
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
            renderCell: (params)=><TimestampFormatter relative={false}
                                                      value={params.getValue("last_modified") as string}
                                                      formatString={"HH:mm ddd Do MMM YYYY Z"}
            />,
            width: 250
        },
        {
            field: "proxied",
            headerName: "Proxied?",
        },
        {
            field: "id",
            headerName: " ",
            width: 150,
            renderCell: (params)=><Grid container direction="row" spacing={0}>
                <Grid item>
                    <Tooltip title="Remove this tombstone">
                        <IconButton onClick={()=>props.requestDelete(params.value as string)}>
                            <DeleteForever/>
                        </IconButton>
                    </Tooltip>
                </Grid>
                <Grid item>
                    <Tooltip title="View the item">
                        <IconButton onClick={()=>props.requestOpen(params.value as string)}>
                            <Launch/>
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