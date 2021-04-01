import React, {useEffect, useState} from "react";
import {AdvancedSearchDoc, DeletionSummaryResponse} from "../types";
import axios from "axios";
import {formatError} from "../common/ErrorViewComponent";
import BrowseSummaryDisplay from "../browse/BrowseSummaryDisplay";
import {Button, Grid, LinearProgress, makeStyles} from "@material-ui/core";
import {DeleteForever} from "@material-ui/icons";
import {baseStyles} from "../BaseStyles";

interface DeletedItemSummaryProps {
    collectionName:string;
    path:string|undefined;
    parentIsLoading: boolean;
    loadingNotifiction?: string;
    searchDoc: AdvancedSearchDoc;
    onError?: (errorDesc:string)=>void;
    goToRootCb:()=>void;
    refreshCb:()=>void;
    requestRemoveAll:()=>void;
}

const useStyles = makeStyles(baseStyles);

const DeletedItemSummary:React.FC<DeletedItemSummaryProps> = (props) => {
    const [summaryData, setSummaryData] = useState<DeletionSummaryResponse|undefined>(undefined);

    const classes = useStyles();

    useEffect(()=>{
        if(props.collectionName!="") {
            loadData();
        }
    }, [props.collectionName, props.path, props.searchDoc]);

    const loadData = async ()=>{
        try {
            let args = "";
            if(props.path) {
                args = `?prefix=${encodeURIComponent(props.path)}`
            }

            const response = await axios.put<DeletionSummaryResponse>(`/api/deleted/${props.collectionName}/summary${args}`, props.searchDoc, {headers: {"Content-Type":"application/json"}});
            setSummaryData(response.data);
        } catch(err) {
            console.error("Could not load deletion summary for ", props.collectionName, ": ", err);
            if(props.onError) {
                props.onError(formatError(err, false));
            }
        }
    }

    return <BrowseSummaryDisplay collectionName={props.collectionName}
                                 path={props.path}
                                 parentIsLoading={props.parentIsLoading}
                                 totalHits={summaryData ? summaryData.totalHits : 0}
                                 totalSize={summaryData ? summaryData.totalSize : 0}
                                 goToRootCb={props.goToRootCb}
                                 refreshCb={props.refreshCb}>
        <Grid item style={{marginLeft: "0.8em"}}>
            <Button variant="outlined"
                    onClick={props.requestRemoveAll}
                    startIcon={<DeleteForever/>}>
                Remove all tombstones
            </Button>
        </Grid>
        <div style={{width:"100%"}}>
            {
                props.parentIsLoading ? <LinearProgress/> : null
            }
            {
                props.parentIsLoading && props.loadingNotifiction ? <p className={classes.centered} style={{marginTop: "0.1em"}}>{props.loadingNotifiction}</p> : null
            }
        </div>
    </BrowseSummaryDisplay>

}

export default DeletedItemSummary;