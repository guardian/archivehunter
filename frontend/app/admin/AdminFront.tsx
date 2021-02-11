import React from "react";
import {RouteComponentProps} from "react-router";
import AdminContainer from "./AdminContainer";
import {Grid, Typography} from "@material-ui/core";
import {Helmet} from "react-helmet";

const AdminFront:React.FC<RouteComponentProps> = (props) => {
    return <AdminContainer {...props}>
        <Helmet>
            <title>Admin - ArchiveHunter</title>
        </Helmet>
        <Grid container direction="column" justify="center">
            <Grid item>
                <Typography variant="h6">Administration</Typography>
                <Typography>Select an administrative section from the list on the left.</Typography>
            </Grid>
        </Grid>
    </AdminContainer>
}

export default AdminFront;