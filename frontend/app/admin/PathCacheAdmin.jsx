import React from "react";
import axios from "axios";
import MuiAlert from "@material-ui/lab/Alert";
import {formatError} from "../common/ErrorViewComponent.jsx";
import AdminContainer from "./AdminContainer";
import {Button, createStyles, withStyles, Paper, Snackbar, Typography} from "@material-ui/core";
import {LibraryBooks} from "@material-ui/icons";
import {Helmet} from "react-helmet";

const styles = createStyles({
    area: {
        padding: "2em"
    }
});

class PathCacheAdmin extends React.Component {
    constructor(props) {
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            showingError:false,
            cachedPathCount: 0,
            reindexWaiting: false,
            reindexSuccessful: false
        };

        this.requestReindex = this.requestReindex.bind(this);
        this.closeError = this.closeError.bind(this);
    }

    updatePathCount() {
        this.setState({loading: true}, ()=>axios.get("/api/pathcache/size")
            .then(response=>{
                this.setState({loading: false, lastError: null, cachedPathCount: response.data.count})
            })
            .catch(err=>{
                console.error("Could not get current path count: ", err);
                this.setState({loading: false, lastError: err, cachedPathCount: 0, showingError: true})
            })
        );
    }

    componentDidMount() {
        this.updatePathCount();
    }

    requestReindex() {
        this.setState({reindexWaiting: true, reindexSuccessful: false}, ()=>axios.put("/api/pathcache/rebuild")
            .then(response=>{
                this.setState({reindexWaiting: false, reindexSuccessful: true})
            })
            .catch(err=>{
                console.error("Could not request reindex: ", err);
                this.setState({reindexWaiting: false, reindexSuccessful: false, lastError: err, showingError:true})
            })
        );
    }

    closeError() {
        this.setState({showingError: false})
    }

    render() {
        return <AdminContainer {...this.props}>
            <Helmet>
                <title>Paths Caching - ArchiveHunter</title>
            </Helmet>
            <Snackbar open={this.state.showingError} onClose={this.closeError} autoHideDuration={8000}>
                {
                    this.state.lastError ? <MuiAlert severity="error" onClose={this.closeError}>{formatError(this.state.lastError, false)}</MuiAlert> : null
                }
            </Snackbar>
                <Paper elevation={3} className={this.props.classes.area}>
                    {this.state.loading ?
                        <Typography>Loading...</Typography> :
                        <Typography>There are currently {this.state.cachedPathCount} cached path fragments</Typography>
                    }
                    <Typography>You can rebuild the index here. It's not blanked before use.  The process should take less than half an hour.</Typography>
                    <Button variant="outlined"
                            startIcon={<LibraryBooks/>}
                            onClick={this.requestReindex}
                            disabled={this.state.reindexSuccessful || this.state.reindexWaiting}
                    >Re-index</Button>
                    {
                        this.state.reindexWaiting ? <p>Waiting....</p> : null
                    }
                    {
                        this.state.reindexSuccessful ? <p>Re-index has been started, you can close this page</p> : null
                    }
                </Paper>
        </AdminContainer>
    }
}

export default withStyles(styles)(PathCacheAdmin);
