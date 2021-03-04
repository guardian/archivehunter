import React, {useEffect, useState} from "react";
import {RouteComponentProps} from "react-router";
import {ArchiveEntry, ObjectGetResponse} from "../types";
import axios from "axios";
import {formatError} from "../common/ErrorViewComponent";
import {CircularProgress, makeStyles, Paper, Snackbar} from "@material-ui/core";
import Helmet from "react-helmet";
import MuiAlert from "@material-ui/lab/Alert";
import clsx from "clsx";
import FlexMetadata from "./FlexMetadata";
import MediaPreview from "../Entry/MediaPreview";

interface ItemViewParams {
    id: string;
}

const useStyles = makeStyles((theme)=>({
    itemWindow: {
        display: "flex",
        flexDirection: "column",
        height: "95vh"
    },
    previewArea: {
        flex: 1,
        maxHeight: "60vh",
        overflow: "hidden"
    },
    infoArea: {
        flex: 1,
        padding: "1em",

    },
    centered: {
        marginLeft: "auto",
        marginRight: "auto",
    },
    inlineThrobber: {
        marginRight: "1em"
    },
    loading: {
        verticalAlign: "baseline"
    }
}));

const ItemView:React.FC<RouteComponentProps<ItemViewParams>> = (props) => {
    const [entry, setEntry] = useState<ArchiveEntry|undefined>(undefined);
    const [loading, setLoading] = useState(true);
    const [lastError, setLastError] = useState<string|undefined>(undefined);
    const [lastInfo, setLastInfo] = useState<string|undefined>(undefined);
    const [showingAlert, setShowingAlert] = useState(false);

    const classes = useStyles();

    const loadEntry = async (entryId:string) => {
        try {
            const response = await axios.get<ObjectGetResponse<ArchiveEntry>>(`/api/entry/${entryId}`);
            setEntry(response.data.entry);
            setLoading(false);
        } catch (err) {
            console.error("Could not load item ", entryId, ": ", err);
            setLoading(false);
            setLastError(formatError(err, false));
            setShowingAlert(true);
        }
    }

    useEffect(()=>{
        if(props.match.params.id && props.match.params.id!="") {
            loadEntry(props.match.params.id);
        } else {
            setLoading(false);
            setLastError("Nothing given to load");
            setShowingAlert(true);
        }
    }, []);

    const closeAlert = ()=> {
        setShowingAlert(false);
    }

    const proxyGenerationWasTriggered = () => {
        setLastInfo("Started proxy generation");
        setShowingAlert(true);
    }

    const subComponentError = (errorDesc:string) => {
        setLastError(errorDesc);
        setShowingAlert(true);
    }

    return <div className={classes.itemWindow}>
        <Helmet>
            <title>{
                entry ? `${entry.path} - Archive Hunter` : "Archive Hunter"
            }</title>
        </Helmet>
        <Snackbar open={showingAlert} onClose={closeAlert} autoHideDuration={8000}>
            <>
            {
                lastError ? <MuiAlert severity="error" onClose={closeAlert}>{lastError}</MuiAlert> : undefined
            }
            {
                lastInfo ? <MuiAlert severity="info" onClose={closeAlert}>{lastInfo}</MuiAlert> : undefined
            }
            </>
        </Snackbar>
        <div className={classes.previewArea}>
            {entry ?
                <MediaPreview itemId={entry.id}
                              mimeType={entry.mimeType}
                              fileExtension={entry.file_extension ?? ".dat"}
                              triggeredProxyGeneration={proxyGenerationWasTriggered}
                              onError={subComponentError}
                              /> : undefined }
            {
                loading ?
                    <span className={clsx(classes.centered, classes.loading)}><CircularProgress className={classes.inlineThrobber}/>Loading...</span> :
                    undefined
            }
        </div>

        <div className={classes.infoArea}>
            <Paper elevation={3} style={{height: "100%"}}>
                {
                    entry ? <FlexMetadata entry={entry}/> : undefined
                }
            </Paper>
        </div>
    </div>
}

export default ItemView;