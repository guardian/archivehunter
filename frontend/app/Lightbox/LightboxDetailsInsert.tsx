import React, {useState} from "react";
import LightboxInfoInsert from "./LightboxInfoInsert";
import AvailabilityInsert from "./AvailabilityInsert";
import {LightboxEntry, RestoreStatus} from "../types";
import {CircularProgress, makeStyles} from "@material-ui/core";

interface LightboxDetailsInsertProps {
    lightboxEntry: LightboxEntry;
    archiveEntryId?: string;
    archiveEntryPath?: string;
    checkArchiveStatusClicked: ()=>void;
    redoRestoreClicked: ()=>void;
    user: string;
    onError?: (errorDesc:string) => void;
}

const useStyles = makeStyles({
    smallSpinner: {
        width: "20px",
        height: "20px"
    }
});

/**
 * this describes an "insert" into the standard entry details view, to provide lightbox-specific data
 */
const LightboxDetailsInsert:React.FC<LightboxDetailsInsertProps> = (props) => {
    const [showingArchiveSpinner, setShowingArchiveSpinner] = useState(false);

    const classes = useStyles();

    const displayInfo = (status:string) => {
        if(status=='RS_UNNEEDED') {
            return "Not in deep freeze";
        } else {
            return undefined;
        }
    }

    const displayRedo = (status:RestoreStatus) => {
        switch(status) {
            case "RS_PENDING":
            case "RS_UNDERWAY":
            case "RS_UNNEEDED":
                return false;
            case "RS_ERROR":
            case "RS_ALREADY":
            case "RS_SUCCESS":
                return true;
        }
    }

    const shouldHideAvailability = () => props.lightboxEntry.restoreStatus!="RS_UNNEEDED" && props.lightboxEntry.restoreStatus!="RS_SUCCESS";
    const extractPath = () =>  props.archiveEntryPath ? props.archiveEntryPath.substring(props.archiveEntryPath.lastIndexOf('/') + 1) : "unknown";

    return <div>
            <LightboxInfoInsert
                entry={props.lightboxEntry}
                extraInfo={displayInfo(props.lightboxEntry.restoreStatus)}
                iconName="file"
            />
            <p className="centered small">
                <a style={{cursor: "pointer"}} onClick={props.checkArchiveStatusClicked}>Re-check</a>
            </p>
            <p className="centered small">
                <a style={{cursor: "pointer"}} onClick={props.redoRestoreClicked}>
                    {displayRedo(props.lightboxEntry.restoreStatus)}
                </a>
                {
                    showingArchiveSpinner ? <CircularProgress className={classes.smallSpinner}/> : null
                }
            </p>
            <p className="centered small information">
                {props.lightboxEntry.restoreStatus}
            </p>
            <hr/>
            <AvailabilityInsert status={props.lightboxEntry.restoreStatus}
                                availableUntil={props.lightboxEntry.availableUntil}
                                hidden={shouldHideAvailability()}
                                fileId={props.archiveEntryId}
                                fileNameOnly={extractPath()}
            />
            <hr style={{display: shouldHideAvailability() ? "inherit" : "none" }}/>
        </div>;
}

export default LightboxDetailsInsert;