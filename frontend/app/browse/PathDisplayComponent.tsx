import React from "react";
import {Grid, makeStyles, Typography} from "@material-ui/core";
import {ChevronRightRounded, FolderRounded} from "@material-ui/icons";

interface PathDisplayComponentProps {
    path: string;
}

const useStyles = makeStyles({
    pathPart: {
        fontSize: "1em",
        paddingTop: "0.08em"
    },
    leadInIcon: {
        marginRight: "0.1em"
    }
});

const PathDisplayComponent:React.FC<PathDisplayComponentProps> = (props) => {
    const parts = props.path.split("/");
    const pathlen = parts.length;

    const classes = useStyles();

    return <Grid container direction="row">
        <Grid item>
            <FolderRounded className={classes.leadInIcon}/>
        </Grid>
        {
            parts.map((pathPart, idx)=> {
               return pathPart=="" ? null : <React.Fragment key={idx}>
                    <Grid item><Typography className={classes.pathPart}>{pathPart}</Typography></Grid>
                   {idx==pathlen-1 ? null : <Grid item><ChevronRightRounded/></Grid>}
               </React.Fragment>
            })
        }
    </Grid>
}

export default PathDisplayComponent;