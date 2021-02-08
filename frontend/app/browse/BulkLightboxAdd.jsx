import React from 'react';
import PropTypes from 'prop-types';
import {FontAwesomeIcon} from "@fortawesome/react-fontawesome";
import axios from 'axios';
import ErrorViewComponent, {formatError} from "../common/ErrorViewComponent.jsx";
import BytesFormatter from "../common/BytesFormatter.jsx";
import {Button, CircularProgress} from "@material-ui/core";
import {WbIncandescent} from "@material-ui/icons";

class BulkLightboxAdd extends React.Component {
    static propTypes = {
        searchDoc: PropTypes.object.isRequired,
        onError: PropTypes.func
    };

    constructor(props){
        super(props);

        this.state = {
            loading: false,
            quotaExceeded: false,
            quotaRequired: -1,
            quotaLevel: -1,
            bulkRecord: null
        };

        this.triggerBulkLightboxing = this.triggerBulkLightboxing.bind(this);
    }

    componentDidMount() {
        this.checkBulkRecord();
    }

    componentDidUpdate(prevProps, prevState, snapshot) {
        console.log("componentDidUpdate: ", this.props.collection, this.props.path);
        if(prevProps.collection !== this.props.collection || prevProps.path !== this.props.path) this.checkBulkRecord();
    }

    /**
     * query the server to see if we are already saved as a bulk
     */
    checkBulkRecord() {
        this.setState({loading: true},
            ()=>axios.put("/api/lightbox/my/bulk/query", this.props.searchDoc, {headers: {"Content-Type": "application/json"}})
                .then(response=>{
                    this.setState({loading: false, bulkRecord: response.data.entry});
                }).catch(err=>{
                    console.error(err);
                    if(this.props.onError) this.props.onError(formatError(err, false));
                    this.setState({loading: false})
                })
        )
    }

    triggerBulkLightboxing() {
        if(this.state.loading) return;
        this.setState({loading: true},
            ()=>axios.put("/api/lightbox/my/addFromSearch", this.props.searchDoc,{headers:{"Content-Type":"application/json"}}).then(response=>{
                console.log(response.data);
                this.setState({loading:false, bulkRecord: response.data.objectId ? response.data.objectId : null});
            }).catch(err=>{
                if(err.response && err.response.status===413){
                    console.log(err.response.data);
                    if(this.props.onError) {
                        this.props.onError("Can't lightbox this as it would exceed your restore quota.")
                    }
                    this.setState({
                        loading: false,
                        quotaExceeded: true,
                        quotaRequired: err.response.data.requiredQuota,
                        quotaLevel: err.response.data.actualQuota
                    })
                } else {
                    if(this.props.onError) this.props.onError(formatError(err, false));
                    this.setState({loading: false});
                }
            })
        )
    }

    render() {
        return <>
            {
                this.state.loading ? <Button disabled={true} startIcon={<CircularProgress/>} variant="contained">Lightboxing...</Button> :
                    <Button startIcon={<WbIncandescent/>} variant="outlined" onClick={this.triggerBulkLightboxing}>Lightbox all</Button>
            }
            </>
    }
}

export default BulkLightboxAdd;