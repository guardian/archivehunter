import React from 'react';
import axios from 'axios';
import PropTypes from 'prop-types';

class InitiateAddComponent extends React.Component {
    static propTypes = {
        willSearchUpdated: PropTypes.func.isRequired
    };

    constructor(props){
        super(props);
        this.state = {
            findBySearch: true
        }

    }

    render(){
        return <div>
            <h3>Add proxy framework</h3>
            <table>
                <tbody>
                <tr>
                    <td>
                        <input type="radio" id="search-option" name="add_option"
                               checked={this.state.findBySearch}
                               onChange={evt=>{
                                   const newValue = evt.target.checked; //needed to re-use in the setState callback
                                   this.setState({findBySearch: newValue},
                                       ()=>this.props.willSearchUpdated(newValue)
                                   )}}
                        />
                        <label htmlFor="search-option" style={{display: "inline", marginLeft: "0.4em"}}>Search Cloudformation for deployments</label>
                    </td>
                </tr>
                <tr>
                    <td>
                        <input type="radio" id="direct-option" name="direct_option"
                               checked={!this.state.findBySearch}
                               onChange={evt => {
                                   const newValue = evt.target.checked;//needed to re-use in the setState callback
                                   this.setState({findBySearch: !newValue},
                                       () => this.props.willSearchUpdated(!newValue)
                                   )
                               }
                               }
                               />
                        <label htmlFor="direct-option" style={{display: "inline", marginLeft: "0.4em"}}>Specify details manually</label>
                    </td>
                </tr>
                </tbody>
            </table>
        </div>;
    }
}

export default InitiateAddComponent;