import React from 'react';
import PropTypes from 'prop-types';
import {LightboxIndex} from "../types";
import {makeStyles} from "@material-ui/core";

interface EntryLightboxBannerProps {
    lightboxEntries: LightboxIndex[];
    small?: boolean;
}

const useStyles = makeStyles({
    entryLightboxBanner: {
        width: "100%",
        height: "16px",
        overflow: "hidden",
        marginLeft: "0.1em",
        marginRight: "0.1em",
    },
    entryLightboxBannerSmall: {
        width: "16px",
        height: "16px",
        marginRight: "0.2em",
        borderRadius: "16px"
    },
    entryLightboxBannerLarge: {
        width: "26px",
        height: "26px",
        marginRight: "0.4em",
        marginBottom: "0.6em",
        borderRadius: "32px"
    }
});

const EntryLightboxBanner:React.FC<EntryLightboxBannerProps> = (props)=> {
    const classes = useStyles();

    return <span className={classes.entryLightboxBanner}>
        {
            props.lightboxEntries.map(entry=>
                <img src={entry.avatarUrl ? entry.avatarUrl : "/static/default-avatar.png"}
                     alt={entry.owner}
                     className={props.small ? classes.entryLightboxBannerSmall : classes.entryLightboxBannerLarge }
                />
            )
        }
    </span>
}

export default EntryLightboxBanner;