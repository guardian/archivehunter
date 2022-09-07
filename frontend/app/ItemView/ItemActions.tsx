import React, {useEffect, useState} from "react";
import {Button, Grid, makeStyles, Tooltip} from "@material-ui/core";
import {GetApp, Help, WbIncandescent, WbIncandescentOutlined} from "@material-ui/icons";
import axios from "axios";
import {ObjectGetResponse, RestoreStatusResponse, StorageClasses} from "../types";
import {formatError} from "../common/ErrorViewComponent";

interface ItemActionsProps {
    storageClass: StorageClasses;
    isInLightbox: boolean;
    itemId: string;
    onError?: (errorDesc:string)=>void;
    lightboxedCb?: (itemId:string)=>void;
}

const useStyles = makeStyles((theme)=>({
    helpContainer: {
        verticalAlign: "baseline",
        fontSize: "0.8em"
    },
    inlineIcon: {
        marginRight: theme.spacing(0.5),
        verticalAlign: "middle"
    },
    mainContainer: {
        marginLeft: "1em",
        marginRight: "1em"
    }
}));

const ItemActions:React.FC<ItemActionsProps> = (props) => {
    const [downloadUrl, setDownloadUrl] = useState<string|undefined>(undefined);
    const [restoreStatus, setRestoreStatus] = useState<string|undefined>(undefined);

    const classes = useStyles();

    const putToLightbox = async () =>{
        try {
            await axios.put("/api/lightbox/my/" + props.itemId);
            if(props.lightboxedCb) await props.lightboxedCb(props.itemId);
        } catch(err) {
            console.error("could not put to lightbox: ", err);
            const errDescription = formatError(err, false);
            if(props.onError) props.onError(errDescription);
        }
    }

    const removeFromLightbox = async ()=>{
        try {
            await axios.delete("/api/lightbox/my/" + props.itemId);
            if(props.lightboxedCb) await props.lightboxedCb(props.itemId);
        } catch(err) {
            console.error("could not remove from lightbox: ", err);
            if(props.onError) props.onError(formatError(err, false));
        }
    }

    const getDownloadURL = async () => {
        try {
            const response = await axios.get<ObjectGetResponse<string>>("/api/download/" + props.itemId);
            setDownloadUrl(response.data.entry);
        } catch(err) {
            console.error("Could not get download URL: ", err);
        }
    }

    const getArchiveStatus = async () => {
        try {
            const response = await axios.get<RestoreStatusResponse>(`/api/archive/status/${props.itemId}`)

            if (response.data.restoreStatus) {
                setRestoreStatus(response.data.restoreStatus.toString());
            } else {
                console.error("Could not get the restore status.");
            }
        } catch (err) {
            console.error(err);
        }
    }

    useEffect(() => {
        getArchiveStatus();
        getDownloadURL();
    }, []);

    return <Grid container spacing={1} className={classes.mainContainer}>
        <Grid item>
            <>
                {
                    restoreStatus=="RS_SUCCESS" || restoreStatus=="RS_UNNEEDED" || restoreStatus=="RS_ALREADY" || props.storageClass=="STANDARD" || props.storageClass=="STANDARD_IA" ? (
                            <Tooltip title="To download the file, right click on this button and select 'Save Link As...'">
                                <a href={downloadUrl} download>
                                    <Button startIcon={<GetApp/>}
                                            variant="outlined"
                                    >
                                        Download
                                    </Button>
                                </a>
                            </Tooltip>
                        )
                        : (
                            <>
                                <Button startIcon={<GetApp/>}
                                        variant="outlined"
                                        disabled={true}
                                >
                                    Download
                                </Button>
                                <Tooltip title={`An item in deep storage must be restored before you can access it. 
                                    This process normally takes four hours or more.  
                                    You must add the item to your Lightbox, using the Lightbox button, to start the restore process and then download
                                    the item from your Lightbox when it is completed`}>
                                    <div className={classes.helpContainer}>
                                        <Help className={classes.inlineIcon}/>Why can't I download this item?
                                    </div>
                                </Tooltip>
                            </>
                        )
                }
            </>
        </Grid>
        <Grid item>
            {
                props.isInLightbox ?
                    <Button startIcon={<WbIncandescentOutlined/>}
                            variant="outlined"
                            onClick={removeFromLightbox}
                    >
                        Remove from Lightbox
                    </Button>
                    : <Button startIcon={<WbIncandescent/>}
                              variant="outlined"
                              onClick={putToLightbox}
                    >
                        Lightbox
                    </Button>
            }
        </Grid>
    </Grid>
}

export default ItemActions;
