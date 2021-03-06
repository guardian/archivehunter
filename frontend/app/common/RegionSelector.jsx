import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import {MenuItem, Select} from "@material-ui/core";

class RegionSelector extends React.Component {
    static propTypes = {
        value: PropTypes.string.isRequired,
        onChange: PropTypes.func.isRequired
    };

    constructor(props){
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            availableRegions: []
        }
    }

    componentDidMount(){
        this.setState({loading: true, lastError: null}, ()=>axios.get("/api/regions")
            .then(response=>{
                this.setState({loading: false, lastError: null, availableRegions: response.data.entries})
            }).catch(err=>{
                console.error(err);
                this.setState({loading: false, lastError: err})
            }))
    }

    render(){
        return <Select onChange={this.props.onChange} value={this.props.value}>
            {
                this.state.availableRegions.map(entry=><MenuItem key={entry} value={entry}>{entry}</MenuItem>)
            }
        </Select>
    }
}

export default RegionSelector;