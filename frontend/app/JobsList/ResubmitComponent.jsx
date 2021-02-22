import React from 'react';
import PropTypes from 'prop-types';
import axios from "axios";
import ErrorViewComponent, {formatError} from "../common/ErrorViewComponent.jsx";
import {createStyles, Grid, IconButton, Tooltip, withStyles} from "@material-ui/core";
import {Check, Replay, WarningRounded} from "@material-ui/icons";
import clsx from "clsx";

const styles = (theme)=>createStyles({
    successIcon: {
        color: theme.palette.success.dark,
    },
    warningIcon: {
        color: theme.palette.warning.dark,
    },
    errorIcon: {
        color: theme.palette.error.dark,
    },
    icon: {
        marginTop: "auto",
        marginBottom: "auto",
        paddingRight: "0.2em",
        width: "24px",
        height: "24px"
    }
});

class ResubmitComponent extends React.Component {
    static propTypes = {
        jobId: PropTypes.string.isRequired,
        visible: PropTypes.bool.isRequired,
        onSuccess: PropTypes.func,
        onFailed: PropTypes.func
    };

    constructor(props){
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            success: false
        };

        this.resubmit = this.resubmit.bind(this);
    }

    resubmit(){
        this.setState({loading:true, lastError: null}, ()=>axios.put("/api/job/rerunproxy/" + this.props.jobId)
            .then(response=>{
                this.setState({loading: false, lastError: null, success: true, attempted: true}, ()=>{
                    if(this.props.onSuccess) this.props.onSuccess(response.data.entry);
                });
            })
            .catch(err=>{
                console.error(err);
                this.setState({loading: false, lastError: err, success:false, attempted: true}, ()=>{
                    if(this.props.onFailed) this.props.onFailed(formatError(err));
                });
            }));
    }

    render(){
        return <Grid container direction="row" alignContent="space-between">
            <Grid item>
                {this.state.success ?
                    <Check className={clsx(this.props.classes.successIcon, this.props.classes.icon)}/> :
                    <Tooltip title="Re-run this job">
                        <IconButton onClick={this.resubmit} disabled={this.state.loading}>
                            <Replay/>
                        </IconButton>
                    </Tooltip>
                }
            </Grid>
            {
                this.state.lastError ?
                    <Grid item>
                        <Tooltip title={formatError(this.state.lastError, true)}>
                            <span>
                        <WarningRounded
                            className={clsx(this.props.classes.icon, this.state.success ? this.props.classes.warningIcon : this.props.classes.errorIcon)}
                        />
                        </span>
                        </Tooltip>
                    </Grid>: null
            }
        </Grid>
    }
}

export default withStyles(styles)(ResubmitComponent);