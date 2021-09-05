import React, {useEffect, useState} from "react";
import {CircularProgress, Grid, IconButton, makeStyles, Typography} from "@material-ui/core";
import axios from "axios";
import {formatError} from "../../common/ErrorViewComponent";
import {FontAwesomeIcon} from "@fortawesome/react-fontawesome";
import EntryLightboxBanner from "../EntryLightboxBanner";
import {Add, RemoveCircle} from "@material-ui/icons";
import {LightboxIndex} from "../../types";

interface LightboxInsertProps {
    isInLightbox: boolean;
    lightboxedCb?: (entryId:string)=>Promise<void>;
    entryId: string;
    lightboxEntries: LightboxIndex[];
    onError?: (description:string)=>void;
}

const useStyles = makeStyles({

});

const LightboxInsert:React.FC<LightboxInsertProps> = (props) => {
    const [lightboxSaving, setLightboxSaving] = useState(false);

    const putToLightbox = async () =>{
        setLightboxSaving(true);
        try {
            await axios.put("/api/lightbox/my/" + props.entryId);
            if(props.lightboxedCb) await props.lightboxedCb(props.entryId);
            setLightboxSaving(false);
        } catch(err) {
            console.error("could not put to lightbox: ", err);
            const errDescription = formatError(err, false);
            if(props.onError) props.onError(errDescription);
        }
    }

    const removeFromLightbox = async ()=>{
        setLightboxSaving(true);
        try {
            await axios.delete("/api/lightbox/my/" + props.entryId);
            if(props.lightboxedCb) await props.lightboxedCb(props.entryId);
            setLightboxSaving(false);
        } catch(err) {
            console.error("could not remove from lightbox: ", err);
            if(props.onError) props.onError(formatError(err, false));
        }
    }

    return (<>
            <Grid container spacing={1} justify="center">
                <Grid item>
                    <FontAwesomeIcon icon="lightbulb"/>
                </Grid>
                <Grid item>
                    <Typography>Lightboxes</Typography>
                </Grid>
            </Grid>
            <EntryLightboxBanner lightboxEntries={props.lightboxEntries}
                                 isInLightbox={props.isInLightbox}
                                 putToLightbox={putToLightbox}
                                 removeFromLightbox={removeFromLightbox}
                                 small={false}
            />
    </>);
}

export default LightboxInsert;